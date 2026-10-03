package ng.cvgfc.api.style

import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.match.MatchAppearanceRepository
import ng.cvgfc.api.match.MatchRepository
import ng.cvgfc.api.match.MatchService
import ng.cvgfc.api.match.MatchStatus
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.member.Role
import ng.cvgfc.api.profile.MemberProfileRepository
import ng.cvgfc.api.profile.PositionGroup
import ng.cvgfc.api.profile.photoUrl
import ng.cvgfc.api.profiling.ProfilingResponseRepository
import ng.cvgfc.api.profiling.Questionnaire
import ng.cvgfc.api.profiling.RoleResult
import ng.cvgfc.api.rating.CardService
import ng.cvgfc.api.rating.RatingWindowRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

data class PlanFit(
    val code: String,
    val name: String,
    val fit: Int,
    /** The three sources, 0–100, when available. Only in detailed views. */
    val self: Int? = null,
    val ratings: Int? = null,
    val matches: Int? = null,
)

data class RoleCount(val code: String, val name: String, val votes: Int)

data class PlayerStyle(
    val memberId: UUID,
    val name: String,
    val fullName: String,
    val jerseyNumber: Short?,
    val photoUrl: String?,
    val position: String?,
    val group: PositionGroup?,
    /** e.g. "Counter-attacking Inside Forward". */
    val label: String?,
    val topPlan: String?,
    val planFits: List<PlanFit>,
    val role: RoleResult?,
    // Detailed (player themself, coach, admin, captain):
    val selfRole: RoleResult? = null,
    val peerRoles: List<RoleCount>? = null,
    val coachRole: RoleResult? = null,
    /** Self and squad pick different roles. */
    val disagree: Boolean? = null,
    val lowConfidence: Boolean? = null,
    val answeredQuestionnaire: Boolean? = null,
)

data class MyStyle(val style: PlayerStyle, val questionnaireDue: Boolean)

data class RoleOption(val code: String, val name: String)

data class RoleRequest(val role: String?)

data class RoleVoteSheet(val roles: List<RoleOption>, val mine: String?, val name: String)

/**
 * A player's football profile: game plan fit from their questionnaire (35%), peer attribute
 * ratings (50%) and results under each plan (15%, once 5+ matches), plus their role.
 */
@Service
class StyleService(
    private val questionnaire: Questionnaire,
    private val responses: ProfilingResponseRepository,
    private val cards: CardService,
    private val windows: RatingWindowRepository,
    private val votes: RoleVoteRepository,
    private val playerRoles: PlayerRoleRepository,
    private val rounds: ProfilingRoundRepository,
    private val matches: MatchRepository,
    private val appearances: MatchAppearanceRepository,
    private val members: MemberRepository,
    private val profiles: MemberProfileRepository,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    private val clubId get() = properties.clubId
    private val plans get() = questionnaire.def.plans

    @Transactional(readOnly = true)
    fun squad(viewer: CurrentMember): List<PlayerStyle> {
        val people = members.findByClubIdOrderByMemberNo(clubId).filter { it.status.isCurrent }
        val detailed = isStaff(viewer)
        return build(people).map { if (detailed || it.memberId == viewer.id) it else it.public() }
    }

    @Transactional(readOnly = true)
    fun of(memberId: UUID, viewer: CurrentMember): PlayerStyle {
        val m = members.findByIdAndClubId(memberId, clubId) ?: throw ApiException.notFound("Member")
        val s = build(listOf(m)).first()
        return if (isStaff(viewer) || viewer.id == memberId) s else s.public()
    }

    @Transactional(readOnly = true)
    fun mine(memberId: UUID): MyStyle {
        val m = members.findById(memberId).orElseThrow()
        val round = rounds.findFirstByClubIdOrderByOpenedAtDesc(clubId)
        val last = responses.findFirstByMemberIdOrderBySubmittedAtDesc(memberId)
        val due = last == null || (round != null && last.submittedAt.isBefore(round.openedAt))
        return MyStyle(build(listOf(m)).first(), due)
    }

    /** Full profiles for selection and chemistry. */
    @Transactional(readOnly = true)
    fun styles(memberIds: Collection<UUID>): Map<UUID, PlayerStyle> =
        build(members.findAllById(memberIds).filter { it.clubId == clubId }).associateBy { it.memberId }

    /** Combined fit per plan, for selection. */
    @Transactional(readOnly = true)
    fun planFits(memberIds: Collection<UUID>): Map<UUID, Map<String, Int>> {
        val people = members.findAllById(memberIds).filter { it.clubId == clubId }
        return build(people).associate { s -> s.memberId to s.planFits.associate { it.code to it.fit } }
    }

    /** Final roles (coach's call, else questionnaire, else the squad's vote). */
    @Transactional(readOnly = true)
    fun roles(memberIds: Collection<UUID>): Map<UUID, RoleResult?> {
        val people = members.findAllById(memberIds).filter { it.clubId == clubId }
        return build(people).associate { it.memberId to it.role }
    }

    @Transactional
    fun setRole(memberId: UUID, req: RoleRequest, actorId: UUID): PlayerStyle {
        val m = members.findByIdAndClubId(memberId, clubId) ?: throw ApiException.notFound("Member")
        val existing = playerRoles.findById(memberId).orElse(null)
        if (req.role == null) {
            existing?.let { playerRoles.delete(it) }
        } else {
            val group = profiles.findById(memberId).orElse(null)?.favouredPosition?.group
                ?: throw ApiException.conflict("no_position", "They need a main position first.")
            if (rolesFor(group).none { it.code == req.role }) throw ApiException.badRequest("invalid_role", "Pick a role for their position.")
            if (existing == null) playerRoles.save(PlayerRole(memberId, req.role, actorId, Instant.now(clock)))
            else existing.apply { roleCode = req.role; setBy = actorId; setAt = Instant.now(clock) }
        }
        playerRoles.flush()
        return build(listOf(m)).first()
    }

    @Transactional
    fun openRound(actorId: UUID): Instant {
        val r = rounds.save(ProfilingRound(clubId, Instant.now(clock), actorId))
        return r.openedAt
    }

    // ---------- peer role vote (inside a rating window) ----------

    @Transactional(readOnly = true)
    fun roleSheet(raterId: UUID, rateeId: UUID): RoleVoteSheet {
        val w = openWindow() ?: throw ApiException.conflict("no_window", "Ratings are closed.")
        val m = members.findByIdAndClubId(rateeId, clubId) ?: throw ApiException.notFound("Player")
        val group = profiles.findById(rateeId).orElse(null)?.favouredPosition?.group
        val mine = votes.findByIdWindowIdAndIdRaterId(w.id, raterId).firstOrNull { it.id.rateeId == rateeId }?.roleCode
        return RoleVoteSheet(group?.let(::rolesFor).orEmpty(), mine, MatchService.shortName(m))
    }

    @Transactional
    fun voteRole(raterId: UUID, rateeId: UUID, req: RoleRequest): RoleVoteSheet {
        val w = openWindow() ?: throw ApiException.conflict("no_window", "Ratings are closed.")
        if (raterId == rateeId) throw ApiException.badRequest("self_vote", "Your own role comes from your questionnaire.")
        val rater = members.findByIdAndClubId(raterId, clubId)
        if (rater?.status != ng.cvgfc.api.member.MemberStatus.ACTIVE) throw ApiException(HttpStatus.FORBIDDEN, "not_active", "Only active members vote.")
        val group = profiles.findById(rateeId).orElse(null)?.favouredPosition?.group ?: throw ApiException.conflict("no_position", "No position yet.")
        val role = req.role ?: throw ApiException.badRequest("role_required", "Pick a role.")
        if (rolesFor(group).none { it.code == role }) throw ApiException.badRequest("invalid_role", "Pick a role for their position.")
        val key = RoleVoteId(w.id, raterId, rateeId)
        val row = votes.findById(key).orElse(null)
        if (row == null) votes.save(RoleVote(key, role, Instant.now(clock))) else row.apply { roleCode = role; votedAt = Instant.now(clock) }
        votes.flush()
        return roleSheet(raterId, rateeId)
    }

    fun rolesFor(group: PositionGroup): List<RoleOption> = questionnaire.def.roles.getValue(group.name).map { RoleOption(it.code, it.name) }

    fun roleName(code: String): String? = questionnaire.def.roles.values.flatten().firstOrNull { it.code == code }?.name

    // ---------- building profiles ----------

    private fun build(people: List<Member>): List<PlayerStyle> {
        if (people.isEmpty()) return emptyList()
        val ids = people.map { it.id }
        val profileMap = profiles.findAllById(ids).associateBy { it.memberId }
        val selfResults = ids.associateWith { responses.findFirstByMemberIdOrderBySubmittedAtDesc(it)?.result }
        val stats = cards.latestStats(ids)
        val matchFits = matchFits(ids)
        val coach = playerRoles.findAllById(ids).associateBy { it.memberId }
        val peer = peerVotes(ids)

        return people.map { m ->
            val p = profileMap[m.id]
            val group = p?.favouredPosition?.group
            val self = selfResults[m.id]
            val fits = plans.map { plan ->
                val s = self?.planFits?.get(plan.code)
                val r = stats[m.id]?.let { ratingFit(plan.code, group, it) }
                val x = matchFits[m.id]?.get(plan.code)
                PlanFit(plan.code, plan.name, combine(s, r, x) ?: 0, s, r, x)
            }
            val hasData = fits.any { it.self != null || it.ratings != null || it.matches != null }
            val top = if (hasData) fits.maxBy { it.fit } else null
            val peerCounts = peer[m.id].orEmpty()
            val peerTop = peerCounts.firstOrNull()?.let { RoleResult(it.code, it.name) }
            val coachRole = coach[m.id]?.let { c -> roleName(c.roleCode)?.let { RoleResult(c.roleCode, it) } }
            val selfRole = self?.mainRole?.takeIf { group == null || self.group == group }
            val role = coachRole ?: selfRole ?: peerTop
            val adjective = top?.let { t -> plans.first { it.code == t.code }.adjective }
            val label = listOfNotNull(adjective, role?.name ?: p?.favouredPosition?.label).joinToString(" ").takeIf { top != null || role != null }
            PlayerStyle(
                memberId = m.id,
                name = MatchService.shortName(m),
                fullName = m.fullName,
                jerseyNumber = m.jerseyNumber,
                photoUrl = p?.photoId?.let { photoUrl(m.id, it) },
                position = p?.favouredPosition?.name,
                group = group,
                label = label,
                topPlan = top?.code,
                planFits = if (hasData) fits else emptyList(),
                role = role,
                selfRole = selfRole,
                peerRoles = peerCounts.take(3),
                coachRole = coachRole,
                disagree = selfRole != null && peerTop != null && selfRole.code != peerTop.code,
                lowConfidence = self?.lowConfidence,
                answeredQuestionnaire = self != null,
            )
        }
    }

    /** 35/50/15, sharing out the weight of any source that's missing. */
    private fun combine(self: Int?, ratings: Int?, matches: Int?): Int? {
        val parts = listOfNotNull(self?.let { it to 35 }, ratings?.let { it to 50 }, matches?.let { it to 15 })
        if (parts.isEmpty()) return null
        return (parts.sumOf { it.first * it.second }.toDouble() / parts.sumOf { it.second }).roundToInt()
    }

    /** Average of the plan's attributes on the card scale, mapped to 0–100. */
    private fun ratingFit(plan: String, group: PositionGroup?, stats: Map<String, Int>): Int? {
        val attrs = (if (group == PositionGroup.GK) GK_MAP else OUTFIELD_MAP)[plan] ?: return null
        val values = attrs.mapNotNull { stats[it] }
        if (values.size < attrs.size) return null
        return ((values.average() - 30) * 100 / 69).roundToInt().coerceIn(0, 100)
    }

    /** Points per match (win 100, draw 50) under each plan, once a player has 5+ matches under it. */
    private fun matchFits(ids: List<UUID>): Map<UUID, Map<String, Int>> {
        val played = matches.findByClubIdAndStatusOrderByKickoffAtDesc(clubId, MatchStatus.PLAYED).filter { it.gamePlan != null }
        if (played.isEmpty()) return emptyMap()
        val apps = appearances.findByMatchIdIn(played.map { it.id }).filter { it.memberId != null }.groupBy { it.memberId!! }
        val byId = played.associateBy { it.id }
        return ids.associateWith { id ->
            apps[id].orEmpty().mapNotNull { byId[it.matchId] }.groupBy { it.gamePlan!!.name }
                .filterValues { it.size >= MIN_MATCHES }
                .mapValues { (_, ms) ->
                    ms.map { m -> val o = m.ourScore ?: 0; val t = m.theirScore ?: 0; if (o > t) 100 else if (o == t) 50 else 0 }.average().roundToInt()
                }
        }
    }

    /** The squad's role votes from the most recent window that has any. */
    private fun peerVotes(ids: List<UUID>): Map<UUID, List<RoleCount>> {
        for (w in windows.findByClubIdOrderByOpensAtDesc(clubId)) {
            val rows = votes.findByIdWindowIdAndIdRateeIdIn(w.id, ids)
            if (rows.isEmpty()) continue
            return rows.groupBy { it.id.rateeId }.mapValues { (_, l) ->
                l.groupingBy { it.roleCode }.eachCount().map { (code, n) -> RoleCount(code, roleName(code) ?: code, n) }.sortedByDescending { it.votes }
            }
        }
        return emptyMap()
    }

    private fun openWindow() = windows.findByClubIdAndClosedAtIsNull(clubId)?.takeIf { it.isOpen(Instant.now(clock)) }

    private fun isStaff(v: CurrentMember) = v.has(Role.ADMIN) || v.has(Role.COACH) || v.has(Role.CAPTAIN)

    private fun PlayerStyle.public() = copy(
        planFits = planFits.map { PlanFit(it.code, it.name, it.fit) },
        selfRole = null, peerRoles = null, coachRole = null, disagree = null, lowConfidence = null, answeredQuestionnaire = null,
    )

    companion object {
        const val MIN_MATCHES = 5

        val OUTFIELD_MAP = mapOf(
            "POS" to listOf("PAS", "VIS", "CTL", "CMP"),
            "CTR" to listOf("PAC", "MOV", "FIN", "DRI"),
            "PRS" to listOf("STA", "PAC", "TAC"),
            "BLK" to listOf("MRK", "TAC", "STR", "AER"),
            "DIR" to listOf("AER", "STR", "SHO", "PAC"),
        )
        val GK_MAP = mapOf(
            "POS" to listOf("DIS", "CMP"),
            "CTR" to listOf("DIS", "REF"),
            "PRS" to listOf("OVO", "COM"),
            "BLK" to listOf("REF", "HAN", "GPO"),
            "DIR" to listOf("DIS", "COM"),
        )
    }
}
