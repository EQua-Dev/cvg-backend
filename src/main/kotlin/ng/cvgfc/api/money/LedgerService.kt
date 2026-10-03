package ng.cvgfc.api.money

import ng.cvgfc.api.audit.AuditService
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.profile.MediaAsset
import ng.cvgfc.api.profile.MediaAssetRepository
import ng.cvgfc.api.profile.Photo
import ng.cvgfc.api.profile.ProfileService
import ng.cvgfc.api.profile.sniffImageType
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** The club runs on Abuja time; "today" and month boundaries use it. */
val CLUB_ZONE: ZoneId = ZoneId.of("Africa/Lagos")

const val MAX_AMOUNT_KOBO = 1_000_000_000L // ₦10,000,000: guards against typos like extra zeros

data class Ref(val id: UUID, val name: String)

data class EntryView(
    val id: UUID,
    val direction: Direction,
    val category: Category,
    val categoryLabel: String,
    val amountKobo: Long,
    val member: Ref?,
    val collection: Ref?,
    val method: PaymentMethod,
    val occurredOn: LocalDate,
    val note: String?,
    /** This entry cancels another one. */
    val reversesId: UUID?,
    /** This entry has been cancelled by a later one. */
    val reversed: Boolean,
    val hasReceipt: Boolean,
    /** An expense with no receipt: shown with a warning. */
    val flagged: Boolean,
    val recordedBy: String?,
    val recordedAt: Instant,
)

data class LedgerMonth(
    val month: String,
    val entries: List<EntryView>,
    val inKobo: Long,
    val outKobo: Long,
    /** All-time club balance, not just this month. */
    val balanceKobo: Long,
    val flaggedCount: Int,
)

data class PaymentRequest(
    val memberId: UUID? = null,
    val collectionId: UUID? = null,
    val category: Category? = null,
    val amountKobo: Long,
    val method: PaymentMethod = PaymentMethod.CASH,
    val occurredOn: LocalDate? = null,
    val note: String? = null,
)

data class ExpenseRequest(
    val category: Category,
    val amountKobo: Long,
    val method: PaymentMethod = PaymentMethod.CASH,
    val occurredOn: LocalDate? = null,
    val note: String? = null,
)

data class ReverseRequest(val reason: String? = null)

@Service
class LedgerService(
    private val entries: LedgerEntryRepository,
    private val receipts: LedgerReceiptRepository,
    private val collections: CollectionRepository,
    private val collectionMembers: CollectionMemberRepository,
    private val members: MemberRepository,
    private val media: MediaAssetRepository,
    private val audit: AuditService,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    private val clubId get() = properties.clubId
    private fun today() = LocalDate.now(clock.withZone(CLUB_ZONE))

    /** Money in: dues against a collection, a donation, or any other income. */
    @Transactional
    fun recordPayment(req: PaymentRequest, actorId: UUID): EntryView {
        checkAmount(req.amountKobo)
        val member = req.memberId?.let {
            members.findByIdAndClubId(it, clubId) ?: throw ApiException.notFound("Member")
        }
        val collection = req.collectionId?.let {
            collections.findByIdAndClubId(it, clubId) ?: throw ApiException.notFound("Collection")
        }
        if (collection != null) {
            if (!collection.isOpen) throw ApiException.conflict("collection_closed", "That collection is closed.")
            if (member == null) throw ApiException.badRequest("member_required", "Pick who paid.")
            // Paying for something they weren't listed on adds them to the list.
            val key = CollectionMemberId(collection.id, member.id)
            if (!collectionMembers.existsById(key)) collectionMembers.save(CollectionMember(key))
        }
        val category = collection?.type ?: req.category
            ?: throw ApiException.badRequest("category_required", "What is it for?")

        val entry = entries.save(
            LedgerEntry(
                clubId = clubId,
                direction = Direction.IN,
                category = category,
                amountKobo = req.amountKobo,
                memberId = member?.id,
                collectionId = collection?.id,
                method = req.method,
                occurredOn = checkDate(req.occurredOn),
                note = cleanNote(req.note),
                reversesId = null,
                recordedBy = actorId,
                recordedAt = Instant.now(clock),
            ),
        )
        val who = member?.fullName ?: "unnamed"
        audit.record(actorId, "ledger.payment", "ledger_entry", entry.id,
            "+${naira(entry.amountKobo)} ${category.label} · $who", after = snapshot(entry))
        return view(entry)
    }

    /** Money out. A short note is required so the ledger explains itself. */
    @Transactional
    fun recordExpense(req: ExpenseRequest, actorId: UUID): EntryView {
        checkAmount(req.amountKobo)
        if (!req.category.expense) throw ApiException.badRequest("invalid_category", "Pick an expense type.")
        val note = cleanNote(req.note) ?: throw ApiException.badRequest("note_required", "Say what it was for.")
        val entry = entries.save(
            LedgerEntry(
                clubId = clubId,
                direction = Direction.OUT,
                category = req.category,
                amountKobo = req.amountKobo,
                memberId = null,
                collectionId = null,
                method = req.method,
                occurredOn = checkDate(req.occurredOn),
                note = note,
                reversesId = null,
                recordedBy = actorId,
                recordedAt = Instant.now(clock),
            ),
        )
        audit.record(actorId, "ledger.expense", "ledger_entry", entry.id,
            "−${naira(entry.amountKobo)} ${req.category.label} · $note", after = snapshot(entry))
        return view(entry)
    }

    /** Mistakes are fixed by an opposite entry that points at the original. Nothing is ever deleted. */
    @Transactional
    fun reverse(id: UUID, req: ReverseRequest, actorId: UUID): EntryView {
        val original = entries.findByIdAndClubId(id, clubId) ?: throw ApiException.notFound("Entry")
        if (original.reversesId != null) throw ApiException.conflict("is_reversal", "A correction can't be reversed.")
        if (entries.existsByReversesId(id)) throw ApiException.conflict("already_reversed", "Already reversed.")
        val reason = cleanNote(req.reason) ?: throw ApiException.badRequest("reason_required", "Say why.")

        val reversal = entries.save(
            LedgerEntry(
                clubId = clubId,
                direction = if (original.direction == Direction.IN) Direction.OUT else Direction.IN,
                category = original.category,
                amountKobo = original.amountKobo,
                memberId = original.memberId,
                collectionId = original.collectionId,
                method = original.method,
                occurredOn = today(),
                note = "Reversal: $reason".take(200),
                reversesId = original.id,
                recordedBy = actorId,
                recordedAt = Instant.now(clock),
            ),
        )
        audit.record(actorId, "ledger.reversal", "ledger_entry", reversal.id,
            "Reversed ${naira(original.amountKobo)} ${original.category.label}: $reason",
            before = snapshot(original), after = snapshot(reversal))
        return view(reversal)
    }

    @Transactional
    fun attachReceipt(id: UUID, bytes: ByteArray, actorId: UUID): EntryView {
        val entry = entries.findByIdAndClubId(id, clubId) ?: throw ApiException.notFound("Entry")
        if (receipts.existsById(id)) throw ApiException.conflict("has_receipt", "This entry already has a receipt.")
        if (bytes.size > ProfileService.MAX_PHOTO_BYTES) throw ApiException.badRequest("photo_too_big", "Photo is too big. Try another.")
        val type = sniffImageType(bytes) ?: throw ApiException.badRequest("photo_type", "Use a JPG, PNG or WebP photo.")
        val asset = media.save(MediaAsset(clubId, type, bytes.size, bytes))
        receipts.save(LedgerReceipt(entry.id, asset.id, actorId, Instant.now(clock)))
        audit.record(actorId, "ledger.receipt_added", "ledger_entry", entry.id, "Receipt for ${naira(entry.amountKobo)} ${entry.category.label}")
        return view(entry)
    }

    @Transactional(readOnly = true)
    fun receipt(id: UUID): Photo? {
        entries.findByIdAndClubId(id, clubId) ?: return null
        val r = receipts.findById(id).orElse(null) ?: return null
        val asset = media.findById(r.mediaId).orElse(null) ?: return null
        return Photo(asset.contentType, asset.data, "\"${asset.id}\"")
    }

    @Transactional(readOnly = true)
    fun month(month: YearMonth?): LedgerMonth {
        val m = month ?: YearMonth.from(today())
        val list = entries.findByClubIdAndOccurredOnBetweenOrderByOccurredOnDescRecordedAtDesc(clubId, m.atDay(1), m.atEndOfMonth())
        val views = views(list)
        return LedgerMonth(
            month = m.toString(),
            entries = views,
            inKobo = list.filter { it.direction == Direction.IN }.sumOf { it.amountKobo },
            outKobo = list.filter { it.direction == Direction.OUT }.sumOf { it.amountKobo },
            balanceKobo = entries.balance(clubId),
            flaggedCount = views.count { it.flagged },
        )
    }

    @Transactional(readOnly = true)
    fun entryMemberId(id: UUID): UUID? = entries.findByIdAndClubId(id, clubId)?.memberId

    fun view(e: LedgerEntry) = views(listOf(e)).first()

    /** Builds views for many entries with a handful of queries rather than one per entry. */
    fun views(list: List<LedgerEntry>): List<EntryView> {
        if (list.isEmpty()) return emptyList()
        val ids = list.map { it.id }
        val withReceipt = receipts.findByEntryIdIn(ids).map { it.entryId }.toSet()
        val reversed = entries.findByReversesIdIn(ids).mapNotNull { it.reversesId }.toSet()
        val people = members.findAllById(list.flatMap { listOfNotNull(it.memberId, it.recordedBy) }.toSet())
            .associate { it.id to it.fullName }
        val titles = collections.findAllById(list.mapNotNull { it.collectionId }.toSet()).associate { it.id to it.title }
        return list.map { e ->
            val isReversed = e.id in reversed
            val hasReceipt = e.id in withReceipt
            EntryView(
                id = e.id,
                direction = e.direction,
                category = e.category,
                categoryLabel = e.category.label,
                amountKobo = e.amountKobo,
                member = e.memberId?.let { Ref(it, people[it] ?: "—") },
                collection = e.collectionId?.let { Ref(it, titles[it] ?: "—") },
                method = e.method,
                occurredOn = e.occurredOn,
                note = e.note,
                reversesId = e.reversesId,
                reversed = isReversed,
                hasReceipt = hasReceipt,
                flagged = e.direction == Direction.OUT && e.reversesId == null && !isReversed && !hasReceipt,
                recordedBy = e.recordedBy?.let { people[it] },
                recordedAt = e.recordedAt,
            )
        }
    }

    private fun checkAmount(kobo: Long) {
        if (kobo <= 0) throw ApiException.badRequest("invalid_amount", "Enter an amount.")
        if (kobo > MAX_AMOUNT_KOBO) throw ApiException.badRequest("invalid_amount", "That amount looks too big.")
    }

    private fun checkDate(date: LocalDate?): LocalDate {
        val d = date ?: today()
        if (d.isAfter(today())) throw ApiException(HttpStatus.BAD_REQUEST, "future_date", "Date can't be in the future.")
        return d
    }

    private fun cleanNote(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() }?.take(200)

    private fun snapshot(e: LedgerEntry): Map<String, Any?> = linkedMapOf(
        "direction" to e.direction.name,
        "category" to e.category.name,
        "amountKobo" to e.amountKobo,
        "memberId" to e.memberId?.toString(),
        "collectionId" to e.collectionId?.toString(),
        "method" to e.method.name,
        "occurredOn" to e.occurredOn.toString(),
        "note" to e.note,
        "reversesId" to e.reversesId?.toString(),
    )
}

/** "₦2,000" or "₦2,500.50". */
fun naira(kobo: Long): String {
    val whole = "%,d".format(kobo / 100)
    val rest = kobo % 100
    return if (rest == 0L) "₦$whole" else "₦$whole.%02d".format(rest)
}
