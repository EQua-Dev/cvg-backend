package ng.cvgfc.api.match

import ng.cvgfc.api.audit.AuditService
import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.member.Role
import ng.cvgfc.api.money.Audience
import ng.cvgfc.api.money.CLUB_ZONE
import ng.cvgfc.api.money.Category
import ng.cvgfc.api.money.CollectionService
import ng.cvgfc.api.money.CreateCollectionRequest
import ng.cvgfc.api.profile.MemberProfileRepository
import ng.cvgfc.api.profile.Position
import ng.cvgfc.api.profile.PositionGroup
import ng.cvgfc.api.profile.photoUrl
import ng.cvgfc.api.season.SeasonService
import ng.cvgfc.api.training.Availability
import ng.cvgfc.api.training.AvailabilityRequest
import ng.cvgfc.api.training.MyAvailability
import ng.cvgfc.api.training.OutReason
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

// ---------- requests ----------

data class MatchRequest(
    val opponent: String,
    val date: LocalDate,
    val kickoff: LocalTime,
    val meetTime: LocalTime? = null,
    val venue: String,
    val side: MatchSide = MatchSide.HOME,
    val type: MatchType = MatchType.FRIENDLY,
    val teamSize: Int = 11,
    val gamePlan: GamePlan? = null,
    val planB: GamePlan? = null,
    val kit: String? = null,
    val feeKobo: Long? = null,
    val notes: String? = null,
)

data class SlotRequest(val idx: Int, val memberId: UUID? = null, val guestName: String? = null)

data class LineupRequest(
    val formation: String,
    val slots: List<SlotRequest>,
    val captainId: UUID? = null,
    val penaltyTakerId: UUID? = null,
    val freeKickTakerId: UUID? = null,
    val cornerTakerId: UUID? = null,
)

data class AppearanceRequest(val memberId: UUID? = null, val guestName: String? = null, val started: Boolean = true, val position: String? = null)

data class GoalRequest(
    val scorerId: UUID? = null,
    val scorerGuest: String? = null,
    val ownGoal: Boolean = false,
    val assistId: UUID? = null,
    val assistGuest: String? = null,
    val minute: Int? = null,
    val kind: GoalKind? = null,
)

data class CardRequest(val memberId: UUID, val colour: CardColour, val minute: Int? = null)

data class ResultRequest(
    val ourScore: Int,
    val theirScore: Int,
    val appearances: List<AppearanceRequest>,
    val goals: List<GoalRequest> = emptyList(),
    val cards: List<CardRequest> = emptyList(),
)

data class VoteRequest(val nomineeId: UUID)

data class OpinionRequest(
    val commendTags: List<String> = emptyList(),
    val commendText: String? = null,
    val critiqueTags: List<String> = emptyList(),
    val critiqueText: String? = null,
    val selfRating: Int? = null,
)

// ---------- views ----------

enum class LineupRole { STARTING, BENCH }

data class MatchView(
    val id: UUID,
    val opponent: String,
    val kickoffAt: Instant,
    val date: LocalDate,
    val time: LocalTime,
    val meetTime: LocalTime?,
    val venue: String,
    val side: MatchSide,
    val type: MatchType,
    val teamSize: Int,
    val gamePlan: GamePlan?,
    val planB: GamePlan?,
    val kit: String?,
    val feeKobo: Long?,
    val notes: String?,
    val status: MatchStatus,
    val formation: String?,
    val lineupPublished: Boolean,
    val ourScore: Int?,
    val theirScore: Int?,
    /** W, D or L once played. */
    val outcome: String?,
    val inCount: Int,
    val outCount: Int,
    val me: MyAvailability?,
    /** Where the viewer is in the published lineup. */
    val myLineup: LineupRole?,
    val potmOpen: Boolean,
    val votedPotm: Boolean,
    val gaveOpinion: Boolean,
    /** The viewer was in the squad, so can vote and give an opinion. */
    val inSquad: Boolean,
)

data class SquadRow(
    val memberId: UUID,
    val fullName: String,
    val nickname: String?,
    val jerseyNumber: Short?,
    val position: Position?,
    val availability: Availability,
    val reason: OutReason?,
)

data class SlotView(
    val idx: Int,
    val position: Position?,
    val x: Int?,
    val y: Int?,
    val memberId: UUID?,
    val guestName: String?,
    val name: String?,
    val jerseyNumber: Short?,
    val photoUrl: String?,
)

data class LineupView(
    val formation: String,
    val slots: List<SlotView>,
    val bench: List<SlotView>,
    val benchSize: Int,
    val captainId: UUID?,
    val penaltyTakerId: UUID?,
    val freeKickTakerId: UUID?,
    val cornerTakerId: UUID?,
    val publishedAt: Instant?,
)

data class PersonRef(val memberId: UUID?, val guestName: String?, val name: String, val jerseyNumber: Short?)

data class GoalView(val seq: Int, val scorer: PersonRef?, val ownGoal: Boolean, val assist: PersonRef?, val minute: Int?, val kind: GoalKind?)

data class AppearanceView(val person: PersonRef, val started: Boolean, val position: String?)

data class CardView(val person: PersonRef, val colour: CardColour, val minute: Int?)

data class ResultView(
    val ourScore: Int,
    val theirScore: Int,
    val goals: List<GoalView>,
    val appearances: List<AppearanceView>,
    val cards: List<CardView>,
    /** GK and defenders who started, when nothing was conceded. */
    val cleanSheets: List<UUID>,
    val feeCollectionId: UUID?,
)

data class Nominee(val memberId: UUID, val name: String, val jerseyNumber: Short?, val photoUrl: String?)

data class Tally(val memberId: UUID, val name: String, val votes: Int)

data class PotmView(
    val open: Boolean,
    val closesAt: Instant?,
    val canVote: Boolean,
    val myVote: UUID?,
    val votesCast: Int,
    val voters: Int,
    val nominees: List<Nominee>,
    /** Only once the vote has closed. */
    val tally: List<Tally>?,
    val winners: List<Tally>,
)

data class TagCount(val tag: String, val praised: Int, val criticised: Int)

data class OpinionView(
    val authorId: UUID?,
    val authorName: String?,
    val commendTags: List<String>,
    val commendText: String?,
    val critiqueTags: List<String>,
    val critiqueText: String?,
    val selfRating: Int?,
)

data class OpinionsView(val count: Int, val tags: List<TagCount>, val items: List<OpinionView>, val mine: OpinionView?, val allTags: List<String>)

data class MatchDetail(
    val match: MatchView,
    val lineup: LineupView?,
    val result: ResultView?,
    val potm: PotmView?,
    val opinions: OpinionsView?,
)

data class MatchOptions(val formations: List<Formation>, val gamePlans: List<Map<String, String>>, val opponents: List<String>, val venues: List<String>, val tags: List<String>)

@Service
class MatchService(
    private val matches: MatchRepository,
    private val availability: MatchAvailabilityRepository,
    private val slots: LineupSlotRepository,
    private val appearances: MatchAppearanceRepository,
    private val goals: MatchGoalRepository,
    private val cards: MatchCardRepository,
    private val votes: PotmVoteRepository,
    private val opinions: MatchOpinionRepository,
    private val members: MemberRepository,
    private val profiles: MemberProfileRepository,
    private val collections: CollectionService,
    private val seasons: SeasonService,
    private val audit: AuditService,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    private val clubId get() = properties.clubId
    private fun now() = Instant.now(clock)
    private fun today() = LocalDate.now(clock.withZone(CLUB_ZONE))
    private fun localDate(i: Instant) = i.atZone(CLUB_ZONE).toLocalDate()

    // ---------- fixtures ----------

    @Transactional(readOnly = true)
    fun options(): MatchOptions {
        val all = matches.findByClubIdOrderByKickoffAtDesc(clubId)
        return MatchOptions(
            formations = Formations.all,
            gamePlans = GamePlan.entries.map { mapOf("code" to it.name, "label" to it.label) },
            opponents = all.map { it.opponent }.distinctBy { it.lowercase() }.take(30),
            venues = all.map { it.venue }.distinctBy { it.lowercase() }.take(15),
            tags = OPINION_TAGS,
        )
    }

    @Transactional
    fun create(req: MatchRequest, actorId: UUID): MatchView {
        val m = Match(clubId, seasons.current()?.id, "", Instant.EPOCH, "", req.side, req.type, 11, actorId)
        applyRequest(m, req)
        if (req.date.isBefore(today())) throw ApiException.badRequest("past_date", "Pick today or later.")
        matches.save(m)
        audit.record(actorId, "match.created", "match", m.id, "vs ${m.opponent} on ${req.date} at ${m.venue}")
        return views(listOf(m), null).first()
    }

    @Transactional
    fun update(id: UUID, req: MatchRequest, actorId: UUID): MatchView {
        val m = get(id)
        if (m.status == MatchStatus.CANCELLED) throw ApiException.conflict("cancelled", "This match was cancelled.")
        val sizeChanged = req.teamSize != m.teamSize.toInt()
        applyRequest(m, req)
        if (sizeChanged) {
            slots.deleteByIdMatchId(m.id)
            m.formation = null
            m.lineupPublishedAt = null
        }
        audit.record(actorId, "match.updated", "match", m.id, "vs ${m.opponent} on ${req.date}")
        return views(listOf(m), null).first()
    }

    private fun applyRequest(m: Match, req: MatchRequest) {
        val opponent = req.opponent.trim().takeIf { it.isNotEmpty() }?.take(80) ?: throw ApiException.badRequest("opponent_required", "Who are we playing?")
        val venue = req.venue.trim().takeIf { it.isNotEmpty() }?.take(80) ?: throw ApiException.badRequest("venue_required", "Where?")
        if (req.teamSize !in setOf(5, 7, 9, 11)) throw ApiException.badRequest("invalid_size", "Pick a format.")
        if (req.feeKobo != null && req.feeKobo <= 0) throw ApiException.badRequest("invalid_fee", "Enter a fee or leave it empty.")
        m.opponent = opponent
        m.venue = venue
        m.kickoffAt = ZonedDateTime.of(req.date, req.kickoff, CLUB_ZONE).toInstant()
        m.meetAt = req.meetTime?.let { ZonedDateTime.of(req.date, it, CLUB_ZONE).toInstant() }
        m.side = req.side
        m.type = req.type
        m.teamSize = req.teamSize.toShort()
        m.gamePlan = req.gamePlan
        m.planB = req.planB?.takeIf { it != req.gamePlan }
        m.kit = req.kit?.trim()?.takeIf { it.isNotEmpty() }?.take(30)
        m.feeKobo = req.feeKobo
        m.notes = req.notes?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
    }

    @Transactional
    fun cancel(id: UUID, actorId: UUID): MatchView {
        val m = get(id)
        if (m.status != MatchStatus.SCHEDULED) throw ApiException.conflict("not_scheduled", "Only upcoming matches can be cancelled.")
        m.status = MatchStatus.CANCELLED
        audit.record(actorId, "match.cancelled", "match", m.id, "Cancelled vs ${m.opponent} (${localDate(m.kickoffAt)})")
        return views(listOf(m), null).first()
    }

    @Transactional(readOnly = true)
    fun list(past: Boolean, me: CurrentMember): List<MatchView> {
        val startOfToday = today().atStartOfDay(CLUB_ZONE).toInstant()
        val list = if (past) {
            matches.findByClubIdOrderByKickoffAtDesc(clubId).filter { it.status == MatchStatus.PLAYED || it.kickoffAt.isBefore(startOfToday) }.take(40)
        } else {
            matches.findByClubIdAndKickoffAtAfterOrderByKickoffAt(clubId, startOfToday).filter { it.status != MatchStatus.PLAYED }
        }
        return views(list, me.id)
    }

    @Transactional(readOnly = true)
    fun detail(id: UUID, me: CurrentMember): MatchDetail {
        val m = get(id)
        val staff = isStaff(me)
        val view = views(listOf(m), me.id).first()
        val lineup = if (m.formation != null && (staff || m.lineupPublishedAt != null)) lineupView(m) else null
        val played = m.resultSavedAt != null
        return MatchDetail(
            match = view,
            lineup = lineup,
            result = if (played) resultView(m) else null,
            potm = if (played) potmView(m, me.id) else null,
            opinions = if (played) opinionsView(m, me) else null,
        )
    }

    // ---------- availability ----------

    @Transactional
    fun setMyAvailability(id: UUID, req: AvailabilityRequest, me: CurrentMember): MatchView {
        val m = get(id)
        if (m.status != MatchStatus.SCHEDULED) throw ApiException.conflict("not_scheduled", "This match is not open.")
        if (!now().isBefore(lockAt(m))) throw ApiException(HttpStatus.CONFLICT, "availability_locked", "Too late to change. Tell the coach.")
        saveAvailability(m, me.id, req, me.id)
        return views(listOf(m), me.id).first()
    }

    @Transactional
    fun setAvailabilityFor(id: UUID, memberId: UUID, req: AvailabilityRequest, actorId: UUID): List<SquadRow> {
        val m = get(id)
        if (m.status != MatchStatus.SCHEDULED) throw ApiException.conflict("not_scheduled", "This match is not open.")
        val member = members.findByIdAndClubId(memberId, clubId) ?: throw ApiException.notFound("Member")
        saveAvailability(m, member.id, req, actorId)
        audit.record(actorId, "match.availability_set", "match", m.id, "${member.fullName}: ${req.status}${req.reason?.let { " ($it)" } ?: ""}")
        return squad(m)
    }

    private fun saveAvailability(m: Match, memberId: UUID, req: AvailabilityRequest, actorId: UUID) {
        if (req.status == Availability.OUT && req.reason == null) throw ApiException.badRequest("reason_required", "Why are you out?")
        val key = MatchMemberId(m.id, memberId)
        val row = availability.findById(key).orElse(null)
        val reason = req.reason.takeIf { req.status == Availability.OUT }
        if (row == null) {
            availability.save(MatchAvailability(key, req.status, reason, actorId, now()))
        } else {
            row.status = req.status
            row.reason = reason
            row.setBy = actorId
            row.setAt = now()
        }
    }

    @Transactional(readOnly = true)
    fun squad(id: UUID): List<SquadRow> = squad(get(id))

    private fun squad(m: Match): List<SquadRow> {
        val avail = availability.findByIdMatchId(m.id).associateBy { it.id.memberId }
        val people = eligible(m)
        val positions = profiles.findAllById(people.map { it.id }).associate { it.memberId to it.favouredPosition }
        return people.map { p ->
            val a = avail[p.id]
            SquadRow(p.id, p.fullName, p.nickname, p.jerseyNumber, positions[p.id], a?.status ?: Availability.IN, a?.reason)
        }.sortedWith(compareBy<SquadRow> { it.position?.ordinal ?: 99 }.thenBy { it.jerseyNumber ?: 999 }.thenBy { it.fullName })
    }

    // ---------- lineup ----------

    /** Saves the coach's board. Re-saving after publishing keeps it published (players see the update). */
    @Transactional
    fun saveLineup(id: UUID, req: LineupRequest, actorId: UUID): LineupView {
        val m = get(id)
        if (m.status != MatchStatus.SCHEDULED) throw ApiException.conflict("not_scheduled", "The lineup can't change now.")
        val formation = Formations.find(m.teamSize.toInt(), req.formation) ?: throw ApiException.badRequest("invalid_formation", "Pick a formation.")
        val benchSize = Formations.benchSize(m.teamSize.toInt())
        val validIdx = formation.slots.map { it.idx }.toSet() + (BENCH_START until BENCH_START + benchSize)
        val clean = req.slots
            .map { it.copy(guestName = it.guestName?.trim()?.takeIf { g -> g.isNotEmpty() }?.take(60)) }
            .filter { it.memberId != null || it.guestName != null }
        if (clean.any { it.idx !in validIdx }) throw ApiException.badRequest("invalid_slot", "That spot isn't in the formation.")
        if (clean.map { it.idx }.toSet().size != clean.size) throw ApiException.badRequest("duplicate_slot", "Two players in one spot.")
        if (clean.any { it.memberId != null && it.guestName != null }) throw ApiException.badRequest("invalid_slot", "A spot is a member or a guest.")
        val memberIds = clean.mapNotNull { it.memberId }
        if (memberIds.toSet().size != memberIds.size) throw ApiException.badRequest("duplicate_player", "A player can only be in one spot.")
        val found = members.findAllById(memberIds.toSet()).filter { it.clubId == clubId && it.status.isCurrent }
        if (found.size != memberIds.size) throw ApiException.notFound("Member")
        for ((field, value) in listOf("captain" to req.captainId, "penalties" to req.penaltyTakerId, "free kicks" to req.freeKickTakerId, "corners" to req.cornerTakerId)) {
            if (value != null && value !in memberIds) throw ApiException.badRequest("not_in_lineup", "Pick $field from the lineup.")
        }

        slots.deleteByIdMatchId(m.id)
        slots.flush()
        slots.saveAll(clean.map { LineupSlot(LineupSlotId(m.id, it.idx.toShort()), it.memberId, it.guestName) })
        m.formation = formation.name
        m.captainId = req.captainId
        m.penaltyTakerId = req.penaltyTakerId
        m.freeKickTakerId = req.freeKickTakerId
        m.cornerTakerId = req.cornerTakerId
        slots.flush()
        return lineupView(m)
    }

    @Transactional
    fun publishLineup(id: UUID, actorId: UUID): LineupView {
        val m = get(id)
        val formation = m.formation?.let { Formations.find(m.teamSize.toInt(), it) } ?: throw ApiException.conflict("no_lineup", "Pick the lineup first.")
        val filled = slots.findByIdMatchId(m.id).filter { !it.bench }.map { it.idx }.toSet()
        val empty = formation.slots.count { it.idx !in filled }
        if (empty > 0) throw ApiException.conflict("lineup_incomplete", "$empty spot${if (empty > 1) "s" else ""} still empty.")
        val first = m.lineupPublishedAt == null
        m.lineupPublishedAt = now()
        audit.record(actorId, if (first) "match.lineup_published" else "match.lineup_republished", "match", m.id, "vs ${m.opponent}: ${formation.name}")
        return lineupView(m)
    }

    private fun lineupView(m: Match): LineupView {
        val formation = Formations.find(m.teamSize.toInt(), m.formation) ?: Formations.default(m.teamSize.toInt())
        val rows = slots.findByIdMatchId(m.id).associateBy { it.idx }
        val people = members.findAllById(rows.values.mapNotNull { it.memberId }).associateBy { it.id }
        val photos = profiles.findAllById(people.keys).associate { it.memberId to it.photoId }
        fun view(idx: Int, slot: FormationSlot?): SlotView {
            val r = rows[idx]
            val p = r?.memberId?.let { people[it] }
            return SlotView(idx, slot?.position, slot?.x, slot?.y, p?.id, r?.guestName, p?.let(::shortName) ?: r?.guestName, p?.jerseyNumber,
                p?.let { photos[it.id]?.let { ph -> photoUrl(it.id, ph) } })
        }
        val benchSize = Formations.benchSize(m.teamSize.toInt())
        return LineupView(
            formation = formation.name,
            slots = formation.slots.map { view(it.idx, it) },
            bench = (BENCH_START until BENCH_START + benchSize).map { view(it, null) },
            benchSize = benchSize,
            captainId = m.captainId,
            penaltyTakerId = m.penaltyTakerId,
            freeKickTakerId = m.freeKickTakerId,
            cornerTakerId = m.cornerTakerId,
            publishedAt = m.lineupPublishedAt,
        )
    }

    // ---------- result ----------

    /**
     * Saves (or corrects) the result. The first save opens the Player of the Match vote for 48 hours
     * and, if the match has a fee, starts a collection for everyone who played.
     */
    @Transactional
    fun saveResult(id: UUID, req: ResultRequest, actorId: UUID): MatchDetail {
        val m = get(id)
        if (m.status == MatchStatus.CANCELLED) throw ApiException.conflict("cancelled", "This match was cancelled.")
        if (localDate(m.kickoffAt).isAfter(today())) throw ApiException.conflict("too_early", "Enter the result on match day.")
        if (req.ourScore !in 0..99 || req.theirScore !in 0..99) throw ApiException.badRequest("invalid_score", "Check the score.")
        if (req.goals.size != req.ourScore) {
            throw ApiException.badRequest("goals_mismatch", "We scored ${req.ourScore}: add ${if (req.ourScore == 1) "the scorer" else "a scorer for each"}.")
        }

        val apps = req.appearances.map { it.copy(guestName = it.guestName?.trim()?.takeIf { g -> g.isNotEmpty() }?.take(60)) }
        if (apps.any { (it.memberId == null) == (it.guestName == null) }) throw ApiException.badRequest("invalid_player", "Each player is a member or a guest.")
        val memberIds = apps.mapNotNull { it.memberId }
        if (memberIds.toSet().size != memberIds.size) throw ApiException.badRequest("duplicate_player", "A player is listed twice.")
        if (members.findAllById(memberIds.toSet()).count { it.clubId == clubId } != memberIds.size) throw ApiException.notFound("Member")
        if (apps.isEmpty()) throw ApiException.badRequest("no_players", "Who played?")
        val played = memberIds.toSet()
        val guests = apps.mapNotNull { it.guestName }.toSet()

        req.goals.forEachIndexed { i, g ->
            val n = i + 1
            if (!g.ownGoal) {
                val ok = (g.scorerId != null && g.scorerId in played) || (g.scorerGuest != null && g.scorerGuest.trim() in guests)
                if (!ok) throw ApiException.badRequest("invalid_scorer", "Goal $n: pick who scored.")
            }
            if (g.assistId != null && g.assistId !in played) throw ApiException.badRequest("invalid_assist", "Goal $n: the assist must be someone who played.")
            if (g.assistId != null && g.assistId == g.scorerId) throw ApiException.badRequest("invalid_assist", "Goal $n: can't assist your own goal.")
            if (g.minute != null && g.minute !in 1..130) throw ApiException.badRequest("invalid_minute", "Goal $n: check the minute.")
        }
        if (req.cards.any { it.memberId !in played }) throw ApiException.badRequest("invalid_card", "Cards are for players who played.")

        val correction = m.resultSavedAt != null
        val before = if (correction) mapOf("score" to "${m.ourScore}-${m.theirScore}") else null
        appearances.deleteByMatchId(m.id)
        goals.deleteByMatchId(m.id)
        cards.deleteByMatchId(m.id)
        appearances.flush()

        appearances.saveAll(apps.map { MatchAppearance(m.id, it.memberId, it.guestName, it.started, it.position?.take(4)) })
        goals.saveAll(req.goals.mapIndexed { i, g ->
            MatchGoal(
                matchId = m.id,
                seq = (i + 1).toShort(),
                scorerMemberId = g.scorerId.takeIf { !g.ownGoal },
                scorerGuest = g.scorerGuest?.trim()?.takeIf { !g.ownGoal && g.scorerId == null && it.isNotEmpty() },
                ownGoal = g.ownGoal,
                assistMemberId = g.assistId,
                assistGuest = g.assistGuest?.trim()?.takeIf { g.assistId == null && it.isNotEmpty() && it in guests },
                minute = g.minute?.toShort(),
                kind = g.kind.takeIf { !g.ownGoal },
            )
        })
        cards.saveAll(req.cards.map { MatchCard(m.id, it.memberId, it.colour, it.minute?.takeIf { x -> x in 1..130 }?.toShort()) })

        m.ourScore = req.ourScore.toShort()
        m.theirScore = req.theirScore.toShort()
        m.status = MatchStatus.PLAYED
        val now = now()
        if (!correction) {
            m.resultSavedAt = now
            m.potmClosesAt = now.plus(POTM_WINDOW)
        }
        if (m.feeKobo != null && m.feeCollectionId == null && played.isNotEmpty()) {
            m.feeCollectionId = collections.create(
                CreateCollectionRequest(
                    title = "Match fee · vs ${m.opponent}".take(80),
                    type = Category.MATCH_FEE,
                    amountKobo = m.feeKobo!!,
                    dueDate = maxOf(today(), localDate(m.kickoffAt)).plusDays(7),
                    audience = Audience.SELECTED,
                    memberIds = played.toList(),
                ),
                actorId,
            ).id
        }
        audit.record(actorId, if (correction) "match.result_corrected" else "match.result_saved", "match", m.id,
            "CVG ${req.ourScore}–${req.theirScore} ${m.opponent}", before = before, after = mapOf("score" to "${req.ourScore}-${req.theirScore}"))
        goals.flush()
        return MatchDetail(views(listOf(m), actorId).first(), lineupView(m).takeIf { m.formation != null }, resultView(m), potmView(m, actorId), null)
    }

    private fun resultView(m: Match): ResultView {
        val apps = appearances.findByMatchId(m.id)
        val goalRows = goals.findByMatchIdOrderBySeq(m.id)
        val cardRows = cards.findByMatchId(m.id)
        val ids = apps.mapNotNull { it.memberId } + goalRows.flatMap { listOfNotNull(it.scorerMemberId, it.assistMemberId) } + cardRows.map { it.memberId }
        val people = members.findAllById(ids.toSet()).associateBy { it.id }
        fun ref(memberId: UUID?, guest: String?): PersonRef? = when {
            memberId != null -> people[memberId]?.let { PersonRef(it.id, null, shortName(it), it.jerseyNumber) }
            guest != null -> PersonRef(null, guest, guest, null)
            else -> null
        }
        val clean = if (m.theirScore?.toInt() == 0) {
            apps.filter { a -> a.started && a.memberId != null && Position.parse(a.position)?.group.let { it == PositionGroup.GK || it == PositionGroup.DEF } }
                .mapNotNull { it.memberId }
        } else emptyList()
        return ResultView(
            ourScore = m.ourScore?.toInt() ?: 0,
            theirScore = m.theirScore?.toInt() ?: 0,
            goals = goalRows.map { GoalView(it.seq.toInt(), ref(it.scorerMemberId, it.scorerGuest), it.ownGoal, ref(it.assistMemberId, it.assistGuest), it.minute?.toInt(), it.kind) },
            appearances = apps.sortedByDescending { it.started }.mapNotNull { a -> ref(a.memberId, a.guestName)?.let { AppearanceView(it, a.started, a.position) } },
            cards = cardRows.mapNotNull { c -> ref(c.memberId, null)?.let { CardView(it, c.colour, c.minute?.toInt()) } },
            cleanSheets = clean,
            feeCollectionId = m.feeCollectionId,
        )
    }

    // ---------- Player of the Match ----------

    @Transactional
    fun vote(id: UUID, req: VoteRequest, me: CurrentMember): PotmView {
        val m = get(id)
        if (!potmOpen(m)) throw ApiException.conflict("vote_closed", "Voting has closed.")
        if (me.id !in squadIds(m)) throw ApiException(HttpStatus.FORBIDDEN, "not_in_squad", "Only the matchday squad votes.")
        if (req.nomineeId == me.id) throw ApiException.badRequest("self_vote", "Vote for a teammate.")
        if (req.nomineeId !in playedIds(m)) throw ApiException.badRequest("invalid_nominee", "Pick someone who played.")
        val key = MatchVoterId(m.id, me.id)
        val existing = votes.findById(key).orElse(null)
        if (existing == null) votes.save(PotmVote(key, req.nomineeId, now())) else existing.apply { nomineeId = req.nomineeId; votedAt = now() }
        votes.flush()
        return potmView(m, me.id)
    }

    @Transactional
    fun closeVote(id: UUID, actorId: UUID): PotmView {
        val m = get(id)
        if (!potmOpen(m)) throw ApiException.conflict("vote_closed", "Voting has already closed.")
        m.potmClosesAt = now()
        val winners = potmView(m, actorId).winners
        audit.record(actorId, "match.potm_closed", "match", m.id, "POTM vs ${m.opponent}: ${winners.joinToString { it.name }.ifEmpty { "no votes" }}")
        return potmView(m, actorId)
    }

    private fun potmOpen(m: Match) = m.resultSavedAt != null && m.potmClosesAt?.isAfter(now()) == true

    private fun potmView(m: Match, viewerId: UUID): PotmView {
        val open = potmOpen(m)
        val rows = votes.findByIdMatchId(m.id)
        val played = appearances.findByMatchId(m.id).mapNotNull { it.memberId }
        val people = members.findAllById(played).associateBy { it.id }
        val photos = profiles.findAllById(played).associate { it.memberId to it.photoId }
        val tally = rows.groupingBy { it.nomineeId }.eachCount()
            .mapNotNull { (id, n) -> people[id]?.let { Tally(id, shortName(it), n) } }
            .sortedByDescending { it.votes }
        val top = tally.firstOrNull()?.votes ?: 0
        val squad = squadIds(m)
        return PotmView(
            open = open,
            closesAt = m.potmClosesAt,
            canVote = open && viewerId in squad,
            myVote = rows.firstOrNull { it.id.voterId == viewerId }?.nomineeId,
            votesCast = rows.size,
            voters = squad.size,
            nominees = played.mapNotNull { people[it] }.filter { it.id != viewerId }
                .sortedBy { it.jerseyNumber ?: 999 }
                .map { Nominee(it.id, shortName(it), it.jerseyNumber, photos[it.id]?.let { ph -> photoUrl(it.id, ph) }) },
            tally = if (open) null else tally,
            winners = if (open || top == 0) emptyList() else tally.filter { it.votes == top },
        )
    }

    // ---------- opinions ----------

    @Transactional
    fun saveOpinion(id: UUID, req: OpinionRequest, me: CurrentMember): OpinionsView {
        val m = get(id)
        if (m.resultSavedAt == null) throw ApiException.conflict("not_played", "Opinions open after the result is in.")
        if (me.id !in squadIds(m)) throw ApiException(HttpStatus.FORBIDDEN, "not_in_squad", "Only the matchday squad gives opinions.")
        fun tags(list: List<String>, what: String): List<String> {
            val clean = list.distinct()
            if (clean.size > 3) throw ApiException.badRequest("too_many_tags", "Pick up to 3 for $what.")
            if (clean.any { it !in OPINION_TAGS }) throw ApiException.badRequest("invalid_tag", "Unknown tag.")
            return clean
        }
        val commend = tags(req.commendTags, "what went well")
        val critique = tags(req.critiqueTags, "what to fix")
        if (commend.isEmpty() && critique.isEmpty()) throw ApiException.badRequest("empty_opinion", "Pick at least one tag.")
        if (req.selfRating != null && req.selfRating !in 1..10) throw ApiException.badRequest("invalid_rating", "Rate yourself 1 to 10.")
        val key = MatchAuthorId(m.id, me.id)
        val o = opinions.findById(key).orElse(null) ?: opinions.save(MatchOpinion(key))
        o.commendTags = commend
        o.commendText = req.commendText?.trim()?.takeIf { it.isNotEmpty() }?.take(280)
        o.critiqueTags = critique
        o.critiqueText = req.critiqueText?.trim()?.takeIf { it.isNotEmpty() }?.take(280)
        o.selfRating = req.selfRating?.toShort()
        o.updatedAt = now()
        opinions.flush()
        return opinionsView(m, me)
    }

    /** Players see opinions without names; admin and coach see who wrote what. */
    private fun opinionsView(m: Match, me: CurrentMember): OpinionsView {
        val rows = opinions.findByIdMatchId(m.id).sortedByDescending { it.updatedAt }
        val named = me.has(Role.ADMIN) || me.has(Role.COACH)
        val authors = if (named) members.findAllById(rows.map { it.id.authorId }).associateBy { it.id } else emptyMap()
        fun view(o: MatchOpinion, withName: Boolean, own: Boolean) = OpinionView(
            authorId = if (withName) o.id.authorId else null,
            authorName = if (withName) authors[o.id.authorId]?.fullName else null,
            commendTags = o.commendTags,
            commendText = o.commendText,
            critiqueTags = o.critiqueTags,
            critiqueText = o.critiqueText,
            selfRating = if (withName || own) o.selfRating?.toInt() else null,
        )
        val tags = OPINION_TAGS.map { t -> TagCount(t, rows.count { t in it.commendTags }, rows.count { t in it.critiqueTags }) }
            .filter { it.praised + it.criticised > 0 }
            .sortedByDescending { it.praised + it.criticised }
        val mine = rows.firstOrNull { it.id.authorId == me.id }
        return OpinionsView(rows.size, tags, rows.map { view(it, named, it.id.authorId == me.id) }, mine?.let { view(it, false, true) }, OPINION_TAGS)
    }

    // ---------- helpers ----------

    fun get(id: UUID) = matches.findByIdAndClubId(id, clubId) ?: throw ApiException.notFound("Match")

    private fun isStaff(me: CurrentMember) = me.has(Role.ADMIN) || me.has(Role.COACH) || me.has(Role.CAPTAIN)

    private fun lockAt(m: Match): Instant = m.kickoffAt.minus(properties.training.availabilityCutoff)

    private fun eligible(m: Match): List<Member> {
        val day = localDate(m.kickoffAt)
        return members.findByClubIdOrderByMemberNo(clubId).filter { it.status.isCurrent && !it.joinedOn.isAfter(day) }
    }

    private fun playedIds(m: Match): Set<UUID> = appearances.findByMatchId(m.id).mapNotNull { it.memberId }.toSet()

    /** The matchday squad: everyone who played, plus anyone named in the published lineup. */
    private fun squadIds(m: Match): Set<UUID> {
        val lineup = if (m.lineupPublishedAt != null) slots.findByIdMatchId(m.id).mapNotNull { it.memberId } else emptyList()
        return playedIds(m) + lineup
    }

    private fun views(list: List<Match>, viewerId: UUID?): List<MatchView> {
        if (list.isEmpty()) return emptyList()
        val ids = list.map { it.id }
        val avail = availability.findByIdMatchIdIn(ids).groupBy { it.id.matchId }
        val lineups = slots.findByIdMatchIdIn(ids).groupBy { it.id.matchId }
        val apps = appearances.findByMatchIdIn(ids).groupBy { it.matchId }
        val myVotes = viewerId?.let { v -> votes.findByIdMatchIdIn(ids).filter { it.id.voterId == v }.map { it.id.matchId }.toSet() } ?: emptySet()
        val myOpinions = viewerId?.let { v -> ids.filter { opinions.existsById(MatchAuthorId(it, v)) }.toSet() } ?: emptySet()
        val now = now()
        val current = members.findByClubIdOrderByMemberNo(clubId).filter { it.status.isCurrent }
        return list.map { m ->
            val day = localDate(m.kickoffAt)
            val eligibleCount = current.count { !it.joinedOn.isAfter(day) }
            val outs = avail[m.id].orEmpty().filter { it.status == Availability.OUT }
            val mine = viewerId?.let { v -> avail[m.id].orEmpty().firstOrNull { it.id.memberId == v } }
            val mySlot = viewerId?.takeIf { m.lineupPublishedAt != null }?.let { v -> lineups[m.id].orEmpty().firstOrNull { it.memberId == v } }
            val inSquad = viewerId != null && (apps[m.id].orEmpty().any { it.memberId == viewerId } || mySlot != null)
            val local = m.kickoffAt.atZone(CLUB_ZONE)
            val our = m.ourScore?.toInt()
            val their = m.theirScore?.toInt()
            MatchView(
                id = m.id,
                opponent = m.opponent,
                kickoffAt = m.kickoffAt,
                date = local.toLocalDate(),
                time = local.toLocalTime(),
                meetTime = m.meetAt?.atZone(CLUB_ZONE)?.toLocalTime(),
                venue = m.venue,
                side = m.side,
                type = m.type,
                teamSize = m.teamSize.toInt(),
                gamePlan = m.gamePlan,
                planB = m.planB,
                kit = m.kit,
                feeKobo = m.feeKobo,
                notes = m.notes,
                status = m.status,
                formation = m.formation,
                lineupPublished = m.lineupPublishedAt != null,
                ourScore = our,
                theirScore = their,
                outcome = if (our == null || their == null) null else if (our > their) "W" else if (our == their) "D" else "L",
                inCount = (eligibleCount - outs.size).coerceAtLeast(0),
                outCount = outs.size,
                me = viewerId?.let {
                    MyAvailability(mine?.status ?: Availability.IN, mine?.reason, !now.isBefore(lockAt(m)) || m.status != MatchStatus.SCHEDULED, lockAt(m))
                },
                myLineup = mySlot?.let { if (it.bench) LineupRole.BENCH else LineupRole.STARTING },
                potmOpen = potmOpen(m),
                votedPotm = m.id in myVotes,
                gaveOpinion = m.id in myOpinions,
                inSquad = inSquad,
            )
        }
    }

    companion object {
        val POTM_WINDOW: Duration = Duration.ofHours(48)

        /** "Tunde A." or the nickname: short enough for a pitch slot. */
        fun shortName(m: Member): String = m.nickname?.takeIf { it.isNotBlank() }
            ?: m.fullName.trim().split(Regex("\\s+")).let { parts -> if (parts.size > 1) "${parts[0]} ${parts.last().first()}." else parts[0] }
    }
}
