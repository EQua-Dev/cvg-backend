package ng.cvgfc.api.match

import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.money.CollectionService
import ng.cvgfc.api.profile.MemberProfileRepository
import ng.cvgfc.api.profile.Position
import ng.cvgfc.api.profiling.ProfilingResponseRepository
import ng.cvgfc.api.training.Availability
import ng.cvgfc.api.training.AttendanceMarkRepository
import ng.cvgfc.api.training.Mark
import ng.cvgfc.api.training.SessionKind
import ng.cvgfc.api.training.SessionStatus
import ng.cvgfc.api.training.TrainingSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.math.roundToInt

/** What the selection score is made of. Weights are percentages and can be changed per match. */
enum class Factor(val defaultWeight: Int) {
    POSITION(25),
    /** Group OVR for the slot's position group, from the latest FUT card. Shared out until anyone has a card. */
    OVR(25),
    PLAN(20),
    ATTENDANCE(15),
    FORM(10),
    DUES(5),
}

data class Candidate(
    val memberId: UUID,
    val name: String,
    val fullName: String,
    val jerseyNumber: Short?,
    val photoUrl: String?,
    val favoured: Position?,
    val otherPositions: List<String>,
    val available: Boolean,
    /** Style label from the questionnaire, e.g. "Counter-attacking Inside Forward". */
    val label: String?,
    /** Each factor scored 0–100. Position is per slot, so it's in [slotScores] instead. */
    val factors: Map<Factor, Int?>,
    /** Slot idx → overall score 0–100 for this player in that spot. */
    val slotScores: Map<Int, Int>,
    /** Best score anywhere with Plan B as the game plan (for the bench), when a Plan B is set. */
    val planBScore: Int?,
    val goals: Int,
    val assists: Int,
    val potmVotes: Int,
    val attendancePercent: Int?,
    /** Latest card OVR per position group. */
    val ovrs: Map<ng.cvgfc.api.profile.PositionGroup, Int?>,
)

data class SelectionView(
    val formation: Formation,
    val benchSize: Int,
    /** The weights actually used after sharing out factors that have no data yet. */
    val weights: Map<Factor, Int>,
    val configured: Map<Factor, Int>,
    val unavailableFactors: List<Factor>,
    val candidates: List<Candidate>,
    /** Auto-fill: slot idx → member. */
    val suggestion: Map<Int, UUID>,
    val benchSuggestion: List<UUID>,
)

data class WeightsRequest(val weights: Map<Factor, Int>)

/**
 * Advice for picking the team. Every available player gets a score for every spot on the pitch,
 * from position fit, game plan fit, attendance, recent form and dues. The coach decides.
 */
@Service
class SelectionService(
    private val matchService: MatchService,
    private val matches: MatchRepository,
    private val availability: MatchAvailabilityRepository,
    private val goals: MatchGoalRepository,
    private val votes: PotmVoteRepository,
    private val members: MemberRepository,
    private val profiles: MemberProfileRepository,
    private val profiling: ProfilingResponseRepository,
    private val sessions: TrainingSessionRepository,
    private val marks: AttendanceMarkRepository,
    private val collections: CollectionService,
    private val cards: ng.cvgfc.api.rating.CardService,
    private val properties: CvgProperties,
) {
    @Transactional
    fun setWeights(matchId: UUID, req: WeightsRequest): Map<Factor, Int> {
        val m = matchService.get(matchId)
        if (req.weights.values.any { it !in 0..100 }) throw ApiException.badRequest("invalid_weight", "Weights go from 0 to 100.")
        if (req.weights.values.sum() == 0) throw ApiException.badRequest("invalid_weight", "At least one factor needs weight.")
        m.weights = Factor.entries.associate { it.name to (req.weights[it] ?: 0) }
        return configured(m)
    }

    @Transactional(readOnly = true)
    fun selection(matchId: UUID, formationName: String?): SelectionView {
        val m = matchService.get(matchId)
        val size = m.teamSize.toInt()
        val formation = Formations.find(size, formationName ?: m.formation) ?: Formations.default(size)
        val configured = configured(m)

        val day = m.kickoffAt
        val people = members.findByClubIdOrderByMemberNo(properties.clubId).filter { it.status.isCurrent }
        val ids = people.map { it.id }
        val outs = availability.findByIdMatchId(m.id).filter { it.status == Availability.OUT }.map { it.id.memberId }.toSet()
        val profileMap = profiles.findAllById(ids).associateBy { it.memberId }
        val styles = ids.associateWith { profiling.findFirstByMemberIdOrderBySubmittedAtDesc(it)?.result }
        val attendance = attendance(ids)
        val form = form(m.id, ids)
        val overdue = collections.overdueMemberIds()

        val ovrs = cards.groupOvrs(ids)
        val maxForm = form.values.maxOfOrNull { it.points } ?: 0.0
        // A factor nobody has data for yet (no ratings, no matches played, no training marked) is set aside.
        val noData = buildList {
            if (ovrs.values.all { g -> g.values.all { it == null } }) add(Factor.OVR)
            if (maxForm == 0.0) add(Factor.FORM)
            if (attendance.values.all { it == null }) add(Factor.ATTENDANCE)
        }
        val unavailable = noData + listOfNotNull(Factor.PLAN.takeIf { m.gamePlan == null })
        val weights = share(configured, unavailable)
        val planBWeights = share(configured, noData)

        val candidates = people.map { p ->
            val profile = profileMap[p.id]
            val style = styles[p.id]
            fun planFit(plan: GamePlan?) = plan?.let { style?.planFits?.get(it.name) }
            val att = attendance[p.id]
            val f = form[p.id]
            val factors = mapOf(
                Factor.OVR to null,
                Factor.PLAN to planFit(m.gamePlan),
                Factor.ATTENDANCE to att,
                Factor.FORM to if (maxForm > 0) ((f?.points ?: 0.0) / maxForm * 100).roundToInt() else 0,
                Factor.DUES to if (p.id in overdue) 0 else 100,
            )
            fun score(slot: FormationSlot, w: Map<Factor, Int>, plan: Int?): Int {
                val values = factors + mapOf(
                    Factor.POSITION to positionFit(slot.position, profile?.favouredPosition, profile?.otherPositions.orEmpty()),
                    Factor.PLAN to plan,
                    Factor.OVR to ovrs[p.id]?.get(slot.position.group)?.let(::ovrScore),
                )
                // Missing data counts as middling, so nobody gains from not filling things in.
                val total = w.entries.sumOf { (factor, weight) -> (values[factor] ?: NEUTRAL) * weight }
                return (total.toDouble() / w.values.sum().coerceAtLeast(1)).roundToInt()
            }
            Candidate(
                memberId = p.id,
                name = MatchService.shortName(p),
                fullName = p.fullName,
                jerseyNumber = p.jerseyNumber,
                photoUrl = profile?.photoId?.let { ng.cvgfc.api.profile.photoUrl(p.id, it) },
                favoured = profile?.favouredPosition,
                otherPositions = profile?.otherPositions.orEmpty(),
                available = p.id !in outs && !p.joinedOn.isAfter(day.atZone(ng.cvgfc.api.money.CLUB_ZONE).toLocalDate()),
                label = style?.label,
                factors = factors,
                slotScores = formation.slots.associate { it.idx to score(it, weights, planFit(m.gamePlan)) },
                planBScore = m.planB?.let { b -> formation.slots.maxOf { score(it, planBWeights, planFit(b)) } },
                goals = f?.goals ?: 0,
                assists = f?.assists ?: 0,
                potmVotes = f?.votes ?: 0,
                attendancePercent = att,
                ovrs = ovrs[p.id].orEmpty(),
            )
        }

        val suggestion = autoFill(formation, candidates.filter { it.available })
        val benchSize = Formations.benchSize(size)
        val bench = candidates.filter { it.available && it.memberId !in suggestion.values }
            .sortedByDescending { it.planBScore ?: it.slotScores.values.maxOrNull() ?: 0 }
            .take(benchSize).map { it.memberId }

        return SelectionView(
            formation = formation,
            benchSize = benchSize,
            weights = weights,
            configured = configured,
            unavailableFactors = unavailable,
            candidates = candidates.sortedWith(compareByDescending<Candidate> { it.available }.thenByDescending { it.slotScores.values.maxOrNull() ?: 0 }),
            suggestion = suggestion,
            benchSuggestion = bench,
        )
    }

    private fun configured(m: Match): Map<Factor, Int> =
        Factor.entries.associateWith { f -> m.weights?.get(f.name) ?: f.defaultWeight }

    /** Drops factors with no data and scales the rest back up to 100. */
    private fun share(configured: Map<Factor, Int>, drop: List<Factor>): Map<Factor, Int> {
        val kept = configured.filterKeys { it !in drop }
        val sum = kept.values.sum()
        if (sum == 0) return kept
        return kept.mapValues { (_, w) -> (w * 100.0 / sum).roundToInt() }
    }

    /** Fills the highest-scoring player/spot pair first, then the next, until the XI is full. */
    private fun autoFill(formation: Formation, available: List<Candidate>): Map<Int, UUID> {
        val pairs = available.flatMap { c -> c.slotScores.map { (idx, s) -> Triple(idx, c.memberId, s) } }
            .sortedWith(compareByDescending<Triple<Int, UUID, Int>> { it.third }.thenBy { it.first })
        val result = linkedMapOf<Int, UUID>()
        val used = mutableSetOf<UUID>()
        for ((idx, member, _) in pairs) {
            if (idx in result || member in used) continue
            result[idx] = member
            used += member
            if (result.size == formation.slots.size) break
        }
        return result.toSortedMap()
    }

    /** Last 8 closed compulsory sessions: % attended of those counted. Null with no record. */
    private fun attendance(ids: List<UUID>): Map<UUID, Int?> {
        val recent = sessions.findByClubIdAndStatusOrderByStartsAt(properties.clubId, SessionStatus.CLOSED)
            .filter { it.kind == SessionKind.COMPULSORY }.takeLast(8)
        val byMember = marks.findByIdSessionIdIn(recent.map { it.id }).groupBy { it.id.memberId }
        return ids.associateWith { id ->
            val mine = byMember[id].orEmpty().map { it.mark }.filter { it != Mark.EXCUSED }
            if (mine.isEmpty()) null else (mine.count { it.attended } * 100.0 / mine.size).roundToInt()
        }
    }

    private data class Form(val goals: Int, val assists: Int, val votes: Int) {
        val points get() = goals + assists * 0.7 + votes * 0.5
    }

    /** Goals, assists and POTM votes over the club's last 5 played matches. */
    private fun form(excludeMatch: UUID, ids: List<UUID>): Map<UUID, Form> {
        val recent = matches.findByClubIdAndStatusOrderByKickoffAtDesc(properties.clubId, MatchStatus.PLAYED)
            .filter { it.id != excludeMatch }.take(5).map { it.id }
        if (recent.isEmpty()) return emptyMap()
        val g = goals.findByMatchIdIn(recent)
        val v = votes.findByIdMatchIdIn(recent)
        return ids.associateWith { id ->
            Form(g.count { it.scorerMemberId == id }, g.count { it.assistMemberId == id }, v.count { it.nomineeId == id })
        }
    }

    companion object {
        private const val NEUTRAL = 50

        /** Card OVR 30–99 on the 0–100 selection scale. */
        fun ovrScore(ovr: Int): Int = ((ovr - 30) * 100.0 / 69).roundToInt().coerceIn(0, 100)

        /** Favoured spot 100, listed other positions 70, same line 50, anything else 30. */
        fun positionFit(slot: Position, favoured: Position?, others: List<String>): Int = when {
            favoured == null -> NEUTRAL
            slot == favoured -> 100
            slot.name in others -> 70
            slot.group == favoured.group -> 50
            else -> 30
        }
    }
}
