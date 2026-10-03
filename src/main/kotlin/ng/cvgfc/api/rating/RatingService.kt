package ng.cvgfc.api.rating

import ng.cvgfc.api.audit.AuditService
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.match.MatchService
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.profile.MemberProfileRepository
import ng.cvgfc.api.profile.PositionGroup
import ng.cvgfc.api.profile.photoUrl
import ng.cvgfc.api.season.SeasonService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class OpenWindowRequest(val title: String? = null, val days: Int = 7)

data class WindowView(
    val id: UUID,
    val title: String,
    val opensAt: Instant,
    val closesAt: Instant,
    val open: Boolean,
    val closedAt: Instant?,
    /** Members who have rated everyone. */
    val finished: Int,
    val raters: Int,
    val cards: Int?,
)

data class RateeRow(
    val memberId: UUID,
    val name: String,
    val jerseyNumber: Short?,
    val photoUrl: String?,
    val position: String?,
    val answered: Int,
    val total: Int,
    val done: Boolean,
    val isMe: Boolean,
)

data class MyRating(val window: WindowView, val players: List<RateeRow>, val done: Int, val total: Int)

data class AttrRow(
    val code: String,
    val label: String,
    val title: String,
    /** 2/4/6/8/10, 0 = "don't know", null = not answered yet. */
    val score: Int?,
    val prefilled: Boolean,
)

data class BlockView(val block: Block, val label: String, val attrs: List<AttrRow>)

data class RateSheet(
    val memberId: UUID,
    val name: String,
    val jerseyNumber: Short?,
    val photoUrl: String?,
    val position: String?,
    val isMe: Boolean,
    val blocks: List<BlockView>,
    val nextMemberId: UUID?,
    val index: Int,
    val total: Int,
)

data class ScoresRequest(val scores: Map<String, Int>)

@Service
class RatingService(
    private val windows: RatingWindowRepository,
    private val ratings: RatingRepository,
    private val cards: PlayerCardRepository,
    private val members: MemberRepository,
    private val profiles: MemberProfileRepository,
    private val seasons: SeasonService,
    private val audit: AuditService,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val clubId get() = properties.clubId
    private fun now() = Instant.now(clock)

    // ---------- windows (admin, coach) ----------

    @Transactional
    fun open(req: OpenWindowRequest, actorId: UUID): WindowView {
        windows.findByClubIdAndClosedAtIsNull(clubId)?.let {
            if (it.isOpen(now())) throw ApiException.conflict("window_open", "A rating window is already open.")
            close(it, null)
        }
        if (req.days !in 1..30) throw ApiException.badRequest("invalid_days", "Pick 1 to 30 days.")
        val season = seasons.current()
        val round = windows.findByClubIdOrderByOpensAtDesc(clubId).count { it.seasonId == season?.id } + 1
        val title = req.title?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: listOfNotNull(season?.name, "Round $round").joinToString(" · ")
        val w = windows.save(RatingWindow(clubId, season?.id, title, now(), now().plus(Duration.ofDays(req.days.toLong())), actorId))
        audit.record(actorId, "ratings.window_opened", "rating_window", w.id, "$title, ${req.days} days")
        return view(w)
    }

    @Transactional
    fun closeNow(id: UUID, actorId: UUID): WindowView {
        val w = windows.findByIdAndClubId(id, clubId) ?: throw ApiException.notFound("Rating window")
        if (w.closedAt != null) throw ApiException.conflict("window_closed", "Already closed.")
        close(w, actorId)
        return view(w)
    }

    /** Closes windows whose end date has passed. Safe to run any time. */
    @Transactional
    fun closeExpired(): Int {
        val w = windows.findByClubIdAndClosedAtIsNull(clubId) ?: return 0
        if (w.isOpen(now())) return 0
        close(w, null)
        return 1
    }

    @Transactional(readOnly = true)
    fun list(): List<WindowView> = windows.findByClubIdOrderByOpensAtDesc(clubId).map(::view)

    // ---------- rating (active members) ----------

    @Transactional(readOnly = true)
    fun mine(raterId: UUID): MyRating? {
        val w = openWindow() ?: return null
        val people = ratees()
        val counts = ratings.findByIdWindowIdAndIdRaterId(w.id, raterId).groupingBy { it.id.rateeId }.eachCount()
        val profileMap = profiles.findAllById(people.map { it.id }).associateBy { it.memberId }
        val total = Attribute.entries.size
        val rows = people.map { m ->
            val p = profileMap[m.id]
            val n = counts[m.id] ?: 0
            RateeRow(m.id, MatchService.shortName(m), m.jerseyNumber, p?.photoId?.let { photoUrl(m.id, it) }, p?.favouredPosition?.name, n, total, n >= total, m.id == raterId)
        }.sortedWith(compareBy<RateeRow> { it.done }.thenBy { !it.isMe }.thenBy { it.jerseyNumber ?: 999 })
        return MyRating(view(w), rows, rows.count { it.done }, rows.size)
    }

    /** One screen per player. The player's own block comes first; last round's answers pre-fill it. */
    @Transactional(readOnly = true)
    fun sheet(raterId: UUID, rateeId: UUID): RateSheet {
        val w = openWindow() ?: throw ApiException.conflict("no_window", "Ratings are closed.")
        ensureRater(raterId)
        val people = ratees()
        val ratee = people.firstOrNull { it.id == rateeId } ?: throw ApiException.notFound("Player")
        val profile = profiles.findById(rateeId).orElse(null)
        val group = profile?.favouredPosition?.group
        val mine = ratings.findByIdWindowIdAndIdRaterIdAndIdRateeId(w.id, raterId, rateeId).associateBy { it.id.attr }
        val previous = if (mine.isEmpty()) lastAnswers(w, raterId, rateeId) else emptyMap()
        val order = Block.entries.sortedBy { if (group != null && it == group.block()) 0 else 1 }
        val blocks = order.map { b ->
            BlockView(b, b.label, Attribute.entries.filter { it.block == b }.map { a ->
                val saved = mine[a.name]
                when {
                    saved != null -> AttrRow(a.name, a.label, a.title, saved.score?.toInt() ?: 0, false)
                    previous.containsKey(a.name) -> AttrRow(a.name, a.label, a.title, previous[a.name], true)
                    else -> AttrRow(a.name, a.label, a.title, null, false)
                }
            })
        }
        // Next: the first player (after this one, wrapping) I haven't finished.
        val done = ratings.findByIdWindowIdAndIdRaterId(w.id, raterId).groupingBy { it.id.rateeId }.eachCount()
        val ordered = people.sortedWith(compareBy<Member> { it.id != raterId }.thenBy { it.jerseyNumber ?: 999 })
        val i = ordered.indexOfFirst { it.id == rateeId }
        val next = (ordered.drop(i + 1) + ordered.take(i)).firstOrNull { (done[it.id] ?: 0) < Attribute.entries.size }
        return RateSheet(rateeId, MatchService.shortName(ratee), ratee.jerseyNumber, profile?.photoId?.let { photoUrl(rateeId, it) },
            profile?.favouredPosition?.name, rateeId == raterId, blocks, next?.id, i + 1, ordered.size)
    }

    /** Saves some or all answers for one player. 0 means "don't know". */
    @Transactional
    fun save(raterId: UUID, rateeId: UUID, req: ScoresRequest): RateSheet {
        val w = openWindow() ?: throw ApiException.conflict("no_window", "Ratings are closed.")
        ensureRater(raterId)
        if (ratees().none { it.id == rateeId }) throw ApiException.notFound("Player")
        val existing = ratings.findByIdWindowIdAndIdRaterIdAndIdRateeId(w.id, raterId, rateeId).associateBy { it.id.attr }
        val now = now()
        for ((code, value) in req.scores) {
            val attr = Attribute.parse(code) ?: throw ApiException.badRequest("invalid_attribute", "Unknown attribute $code.")
            if (value != 0 && value !in CardMath.SCALE) throw ApiException.badRequest("invalid_score", "Use the five steps.")
            val score = value.takeIf { it != 0 }?.toShort()
            val row = existing[attr.name]
            if (row == null) ratings.save(Rating(RatingId(w.id, raterId, rateeId, attr.name), score, now))
            else row.apply { this.score = score; ratedAt = now }
        }
        ratings.flush()
        return sheet(raterId, rateeId)
    }

    // ---------- closing: work out the cards ----------

    private fun close(w: RatingWindow, actorId: UUID?) {
        val all = ratings.findByIdWindowId(w.id)
        val byRatee = all.groupBy { it.id.rateeId }
        val positions = profiles.findAllById(byRatee.keys).associate { it.memberId to it.favouredPosition?.name }
        for ((rateeId, rows) in byRatee) {
            val stats = mutableMapOf<String, Int>()
            val counts = mutableMapOf<String, Int>()
            val self = mutableMapOf<String, Int>()
            for (a in Attribute.entries) {
                val forAttr = rows.filter { it.id.attr == a.name && it.score != null }
                val peers = forAttr.filter { it.id.raterId != rateeId }.map { it.score!!.toInt() }
                val mine = forAttr.firstOrNull { it.id.raterId == rateeId }?.score?.toInt()
                mine?.let { self[a.name] = it * 10 }
                counts[a.name] = peers.size
                CardMath.stat(peers, mine)?.let { stats[a.name] = CardMath.toCard(it) }
            }
            cards.save(PlayerCard(w.id, rateeId, stats, counts, self, positions[rateeId]))
        }
        w.closedAt = now()
        w.closedBy = actorId
        audit.record(actorId, "ratings.window_closed", "rating_window", w.id, "${w.title}: ${byRatee.size} cards")
        log.info("Closed rating window {} with {} cards", w.title, byRatee.size)
    }

    // ---------- helpers ----------

    private fun openWindow(): RatingWindow? = windows.findByClubIdAndClosedAtIsNull(clubId)?.takeIf { it.isOpen(now()) }

    /** Active members rate and are rated (trialists join once they're active). */
    private fun ratees(): List<Member> = members.findByClubIdOrderByMemberNo(clubId).filter { it.status == MemberStatus.ACTIVE }

    private fun ensureRater(raterId: UUID) {
        if (ratees().none { it.id == raterId }) throw ApiException(HttpStatus.FORBIDDEN, "not_active", "Only active members rate.")
    }

    private fun lastAnswers(current: RatingWindow, raterId: UUID, rateeId: UUID): Map<String, Int> {
        val previous = windows.findByClubIdOrderByOpensAtDesc(clubId).firstOrNull { it.id != current.id && it.closedAt != null } ?: return emptyMap()
        return ratings.findByIdWindowIdAndIdRaterIdAndIdRateeId(previous.id, raterId, rateeId).associate { it.id.attr to (it.score?.toInt() ?: 0) }
    }

    private fun view(w: RatingWindow): WindowView {
        val people = ratees()
        val needed = people.size * Attribute.entries.size
        val answered = ratings.answeredPerRater(w.id).associate { (it[0] as UUID) to (it[1] as Long) }
        val ids = people.map { it.id }.toSet()
        return WindowView(
            id = w.id,
            title = w.title,
            opensAt = w.opensAt,
            closesAt = w.closesAt,
            open = w.isOpen(now()),
            closedAt = w.closedAt,
            finished = answered.count { (id, n) -> id in ids && n >= needed },
            raters = people.size,
            cards = if (w.closedAt != null) cards.findByWindowId(w.id).size else null,
        )
    }
}

/** For selection: each player's latest OVR per position group. */
fun PlayerCard.groupOvrs(sets: Map<PositionGroup, List<String>>): Map<PositionGroup, Int?> =
    sets.mapValues { (_, attrs) -> CardMath.ovr(attrs.map { stats[it] }) }
