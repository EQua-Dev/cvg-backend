package ng.cvgfc.api.money

import ng.cvgfc.api.audit.AuditService
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.season.SeasonService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

enum class DueState { PAID, PARTIAL, UNPAID }

data class CollectionSummary(
    val id: UUID,
    val title: String,
    val type: Category,
    val typeLabel: String,
    val amountKobo: Long,
    val dueDate: LocalDate,
    val recurring: Boolean,
    val open: Boolean,
    val overdue: Boolean,
    val memberCount: Int,
    val paidCount: Int,
    val partialCount: Int,
    val expectedKobo: Long,
    val collectedKobo: Long,
)

data class MemberDue(
    val memberId: UUID,
    val fullName: String,
    val nickname: String?,
    val jerseyNumber: Short?,
    val phone: String?,
    val paidKobo: Long,
    val owedKobo: Long,
    val state: DueState,
)

data class CollectionDetail(val summary: CollectionSummary, val members: List<MemberDue>)

data class CreateCollectionRequest(
    val title: String? = null,
    val type: Category,
    val amountKobo: Long,
    val dueDate: LocalDate,
    val audience: Audience = Audience.ACTIVE,
    val memberIds: List<UUID> = emptyList(),
    /** Monthly dues only: a new collection is created on the 1st of every month. */
    val recurring: Boolean = false,
)

data class SetMembersRequest(val memberIds: List<UUID>)

data class MyDue(
    val collectionId: UUID,
    val title: String,
    val dueDate: LocalDate,
    val overdue: Boolean,
    val amountKobo: Long,
    val paidKobo: Long,
    val owedKobo: Long,
    val state: DueState,
)

data class MyDues(val owedKobo: Long, val open: List<MyDue>, val history: List<EntryView>)

@Service
class CollectionService(
    private val collections: CollectionRepository,
    private val collectionMembers: CollectionMemberRepository,
    private val entries: LedgerEntryRepository,
    private val members: MemberRepository,
    private val seasons: SeasonService,
    private val ledger: LedgerService,
    private val audit: AuditService,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val clubId get() = properties.clubId
    private fun today() = LocalDate.now(clock.withZone(CLUB_ZONE))

    @Transactional
    fun create(req: CreateCollectionRequest, actorId: UUID?): CollectionSummary {
        if (!req.type.collectable) throw ApiException.badRequest("invalid_type", "Pick what it's for.")
        if (req.amountKobo <= 0 || req.amountKobo > MAX_AMOUNT_KOBO) throw ApiException.badRequest("invalid_amount", "Enter an amount.")
        if (req.recurring && req.type != Category.DUES) {
            throw ApiException.badRequest("recurring_dues_only", "Only monthly dues can repeat.")
        }
        val period = if (req.recurring) YearMonth.from(req.dueDate).toString() else null
        val title = if (req.recurring) duesTitle(YearMonth.from(req.dueDate))
        else req.title?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
            ?: throw ApiException.badRequest("title_required", "Give it a name.")

        val c = collections.save(
            Collection(
                clubId = clubId,
                seasonId = seasons.current()?.id,
                title = title,
                type = req.type,
                amountKobo = req.amountKobo,
                dueDate = req.dueDate,
                audience = req.audience,
                recurring = req.recurring,
                seriesId = if (req.recurring) UUID.randomUUID() else null,
                period = period,
                createdBy = actorId,
            ),
        )
        val targets = audienceMembers(req.audience, req.memberIds)
        if (targets.isEmpty()) throw ApiException.badRequest("no_members", "Pick at least one member.")
        collectionMembers.saveAll(targets.map { CollectionMember(CollectionMemberId(c.id, it)) })
        audit.record(actorId, "collection.created", "collection", c.id,
            "${c.title}: ${naira(c.amountKobo)} × ${targets.size}")
        return summaries(listOf(c)).first()
    }

    @Transactional(readOnly = true)
    fun list(openOnly: Boolean): List<CollectionSummary> =
        summaries(collections.findByClubIdOrderByDueDateDescCreatedAtDesc(clubId).filter { !openOnly || it.isOpen })

    @Transactional(readOnly = true)
    fun detail(id: UUID): CollectionDetail {
        val c = get(id)
        val ids = collectionMembers.memberIds(id)
        val paid = paidByMember(entries.findByCollectionId(id))
        val people = members.findAllById(ids)
        val rows = people.map { m ->
            val p = paid[m.id] ?: 0
            MemberDue(m.id, m.fullName, m.nickname, m.jerseyNumber, ng.cvgfc.api.common.PhoneNumbers.display(m.phone),
                p, (c.amountKobo - p).coerceAtLeast(0), state(c.amountKobo, p))
        }.sortedWith(compareBy<MemberDue> { it.state.ordinal * -1 }.thenBy { it.fullName })
        return CollectionDetail(summaries(listOf(c)).first(), rows)
    }

    /** Replace who owes. People who already paid something can't be taken off. */
    @Transactional
    fun setMembers(id: UUID, memberIds: List<UUID>, actorId: UUID): CollectionDetail {
        val c = get(id)
        val wanted = memberIds.toSet()
        val known = members.findAllById(wanted).filter { it.clubId == clubId }.map { it.id }.toSet()
        if (known.size != wanted.size) throw ApiException.notFound("Member")
        val paid = paidByMember(entries.findByCollectionId(id))
        val current = collectionMembers.memberIds(id).toSet()
        val removing = current - wanted
        if (removing.any { (paid[it] ?: 0) != 0L }) {
            throw ApiException.conflict("has_paid", "Someone you removed has already paid.")
        }
        collectionMembers.deleteAllById(removing.map { CollectionMemberId(c.id, it) })
        collectionMembers.saveAll((wanted - current).map { CollectionMember(CollectionMemberId(c.id, it)) })
        audit.record(actorId, "collection.members_changed", "collection", c.id,
            "${c.title}: +${(wanted - current).size} −${removing.size}")
        return detail(id)
    }

    @Transactional
    fun close(id: UUID, actorId: UUID): CollectionSummary {
        val c = get(id)
        if (c.isOpen) {
            c.closedAt = Instant.now(clock)
            audit.record(actorId, "collection.closed", "collection", c.id, "Closed ${c.title}")
        }
        return summaries(listOf(c)).first()
    }

    /** Open collections a member is on, plus their payment history. */
    @Transactional(readOnly = true)
    fun myDues(memberId: UUID): MyDues {
        val mine = collections.findAllById(collectionMembers.collectionIdsFor(memberId)).filter { it.isOpen }
        val paid = entries.findByCollectionIdIn(mine.map { it.id })
            .filter { it.memberId == memberId }
            .groupBy { it.collectionId }
            .mapValues { (_, list) -> list.sumOf { it.signedKobo } }
        val items = mine.map { c ->
            val p = paid[c.id] ?: 0
            MyDue(c.id, c.title, c.dueDate, p < c.amountKobo && c.dueDate.isBefore(today()), c.amountKobo, p,
                (c.amountKobo - p).coerceAtLeast(0), state(c.amountKobo, p))
        }.sortedWith(compareBy<MyDue> { it.state == DueState.PAID }.thenBy { it.dueDate })
        val history = ledger.views(entries.findByMemberIdOrderByOccurredOnDescRecordedAtDesc(memberId))
        return MyDues(items.sumOf { it.owedKobo }, items, history)
    }

    /** Members who still owe on an open collection that is past its due date. */
    @Transactional(readOnly = true)
    fun overdueMemberIds(): Set<UUID> {
        val overdue = collections.findByClubIdOrderByDueDateDescCreatedAtDesc(clubId).filter { it.isOpen && it.dueDate.isBefore(today()) }
        if (overdue.isEmpty()) return emptySet()
        val byCollection = entries.findByCollectionIdIn(overdue.map { it.id }).groupBy { it.collectionId!! }
        return overdue.flatMap { c ->
            val paid = paidByMember(byCollection[c.id].orEmpty())
            collectionMembers.memberIds(c.id).filter { (paid[it] ?: 0) < c.amountKobo }
        }.toSet()
    }

    /**
     * On the 1st of each month, each monthly-dues series gets a new collection for that month,
     * for the same audience. Safe to run any number of times (one per series per month).
     */
    @Transactional
    fun rollRecurring(): Int = rollRecurring(today())

    // No default argument here: Kotlin evaluates defaults on the Spring proxy, where fields are null.
    @Transactional
    fun rollRecurring(today: LocalDate): Int {
        val month = YearMonth.from(today)
        var created = 0
        collections.findByClubIdAndRecurringTrue(clubId)
            .groupBy { it.seriesId!! }
            .forEach { (seriesId, series) ->
                val latest = series.maxBy { it.period!! }
                if (YearMonth.parse(latest.period) >= month || collections.existsBySeriesIdAndPeriod(seriesId, month.toString())) return@forEach
                val dueDay = latest.dueDate.dayOfMonth.coerceAtMost(month.lengthOfMonth())
                val next = collections.save(
                    Collection(
                        clubId = clubId,
                        seasonId = seasons.current()?.id,
                        title = duesTitle(month),
                        type = latest.type,
                        amountKobo = latest.amountKobo,
                        dueDate = month.atDay(dueDay),
                        audience = latest.audience,
                        recurring = true,
                        seriesId = seriesId,
                        period = month.toString(),
                        createdBy = null,
                    ),
                )
                val targets = if (latest.audience == Audience.SELECTED) collectionMembers.memberIds(latest.id)
                else audienceMembers(latest.audience, emptyList())
                collectionMembers.saveAll(targets.map { CollectionMember(CollectionMemberId(next.id, it)) })
                audit.record(null, "collection.created", "collection", next.id, "${next.title} (monthly, automatic)")
                created++
            }
        if (created > 0) log.info("Created {} monthly dues collection(s) for {}", created, month)
        return created
    }

    private fun get(id: UUID) = collections.findByIdAndClubId(id, clubId) ?: throw ApiException.notFound("Collection")

    private fun audienceMembers(audience: Audience, selected: List<UUID>): List<UUID> = when (audience) {
        Audience.SELECTED -> {
            val found = members.findAllById(selected.toSet()).filter { it.clubId == clubId }
            if (found.size != selected.toSet().size) throw ApiException.notFound("Member")
            found.map(Member::id)
        }
        Audience.ACTIVE -> members.findByClubIdOrderByMemberNo(clubId).filter { it.status == MemberStatus.ACTIVE }.map(Member::id)
        Audience.ACTIVE_AND_TRIALISTS -> members.findByClubIdOrderByMemberNo(clubId).filter { it.status.isCurrent }.map(Member::id)
    }

    private fun summaries(list: List<Collection>): List<CollectionSummary> {
        if (list.isEmpty()) return emptyList()
        val ids = list.map { it.id }
        val counts = collectionMembers.countsFor(ids).associate { (it[0] as UUID) to (it[1] as Long).toInt() }
        val targets = ids.associateWith { collectionMembers.memberIds(it).toSet() }
        val byCollection = entries.findByCollectionIdIn(ids).groupBy { it.collectionId!! }
        return list.map { c ->
            val paid = paidByMember(byCollection[c.id].orEmpty()).filterKeys { it in targets.getValue(c.id) }
            val states = targets.getValue(c.id).map { state(c.amountKobo, paid[it] ?: 0) }
            val n = counts[c.id] ?: 0
            CollectionSummary(
                id = c.id,
                title = c.title,
                type = c.type,
                typeLabel = c.type.label,
                amountKobo = c.amountKobo,
                dueDate = c.dueDate,
                recurring = c.recurring,
                open = c.isOpen,
                overdue = c.isOpen && c.dueDate.isBefore(today()) && states.any { it != DueState.PAID },
                memberCount = n,
                paidCount = states.count { it == DueState.PAID },
                partialCount = states.count { it == DueState.PARTIAL },
                expectedKobo = c.amountKobo * n,
                collectedKobo = byCollection[c.id].orEmpty().sumOf { it.signedKobo },
            )
        }
    }

    /** Net paid per member: payments minus their reversals. */
    private fun paidByMember(list: List<LedgerEntry>): Map<UUID, Long> =
        list.filter { it.memberId != null }.groupBy { it.memberId!! }.mapValues { (_, l) -> l.sumOf { it.signedKobo } }

    private fun state(amount: Long, paid: Long) = when {
        paid >= amount -> DueState.PAID
        paid > 0 -> DueState.PARTIAL
        else -> DueState.UNPAID
    }

    private fun duesTitle(m: YearMonth) = "${m.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${m.year} dues"
}
