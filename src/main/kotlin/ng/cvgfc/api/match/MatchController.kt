package ng.cvgfc.api.match

import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.money.CLUB_ZONE
import ng.cvgfc.api.training.AvailabilityRequest
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class RecordMatch(
    val matchId: UUID,
    val date: LocalDate,
    val opponent: String,
    val ourScore: Int,
    val theirScore: Int,
    val started: Boolean,
    val goals: Int,
    val assists: Int,
    val potm: Boolean,
)

data class MatchRecord(
    val played: Int,
    val started: Int,
    val goals: Int,
    val assists: Int,
    val potm: Int,
    val cleanSheets: Int,
    val recent: List<RecordMatch>,
)

data class PublicFixture(val date: LocalDate, val time: LocalTime, val opponent: String, val venue: String, val side: MatchSide, val type: MatchType)

data class PublicResult(
    val date: LocalDate,
    val opponent: String,
    val side: MatchSide,
    val ourScore: Int,
    val theirScore: Int,
    val scorers: List<String>,
    val potm: List<String>,
)

data class PublicMatches(val next: PublicFixture?, val results: List<PublicResult>)

/** Per-player match numbers and the public fixtures list. */
@Service
class MatchRecords(
    private val matches: MatchRepository,
    private val appearances: MatchAppearanceRepository,
    private val goals: MatchGoalRepository,
    private val votes: PotmVoteRepository,
    private val members: MemberRepository,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun forMember(memberId: UUID): MatchRecord {
        val apps = appearances.findByMemberId(memberId).associateBy { it.matchId }
        val played = matches.findAllById(apps.keys).filter { it.status == MatchStatus.PLAYED && it.clubId == properties.clubId }
            .sortedByDescending { it.kickoffAt }
        val ids = played.map { it.id }
        val goalRows = goals.findByMatchIdIn(ids).groupBy { it.matchId }
        val voteRows = votes.findByIdMatchIdIn(ids).groupBy { it.id.matchId }
        val now = Instant.now(clock)
        val recent = played.map { m ->
            val g = goalRows[m.id].orEmpty()
            RecordMatch(
                matchId = m.id,
                date = m.kickoffAt.atZone(CLUB_ZONE).toLocalDate(),
                opponent = m.opponent,
                ourScore = m.ourScore?.toInt() ?: 0,
                theirScore = m.theirScore?.toInt() ?: 0,
                started = apps.getValue(m.id).started,
                goals = g.count { it.scorerMemberId == memberId },
                assists = g.count { it.assistMemberId == memberId },
                potm = m.potmClosesAt?.isAfter(now) == false && memberId in winners(voteRows[m.id].orEmpty()),
            )
        }
        val clean = played.count { m ->
            val a = apps.getValue(m.id)
            m.theirScore?.toInt() == 0 && a.started &&
                ng.cvgfc.api.profile.Position.parse(a.position)?.group in setOf(ng.cvgfc.api.profile.PositionGroup.GK, ng.cvgfc.api.profile.PositionGroup.DEF)
        }
        return MatchRecord(recent.size, recent.count { it.started }, recent.sumOf { it.goals }, recent.sumOf { it.assists },
            recent.count { it.potm }, clean, recent.take(10))
    }

    /** Public site: next fixture and recent results. Players are named by nickname or first name only. */
    @Transactional(readOnly = true)
    fun publicMatches(): PublicMatches {
        val now = Instant.now(clock)
        val all = matches.findByClubIdOrderByKickoffAtDesc(properties.clubId)
        val next = all.filter { it.status == MatchStatus.SCHEDULED && it.kickoffAt.isAfter(now) }.minByOrNull { it.kickoffAt }
        val played = all.filter { it.status == MatchStatus.PLAYED }.take(10)
        val ids = played.map { it.id }
        val goalRows = goals.findByMatchIdIn(ids).groupBy { it.matchId }
        val voteRows = votes.findByIdMatchIdIn(ids).groupBy { it.id.matchId }
        val people = members.findAllById(goalRows.values.flatten().mapNotNull { it.scorerMemberId } + voteRows.values.flatten().map { it.nomineeId })
            .associate { it.id to (it.nickname?.takeIf { n -> n.isNotBlank() } ?: it.fullName.trim().split(" ").first()) }
        return PublicMatches(
            next = next?.let {
                val local = it.kickoffAt.atZone(CLUB_ZONE)
                PublicFixture(local.toLocalDate(), local.toLocalTime(), it.opponent, it.venue, it.side, it.type)
            },
            results = played.map { m ->
                val scorers = goalRows[m.id].orEmpty().sortedBy { it.seq }.map { g ->
                    when {
                        g.ownGoal -> "Own goal"
                        g.scorerMemberId != null -> people[g.scorerMemberId] ?: "CVG"
                        else -> g.scorerGuest ?: "CVG"
                    }
                }.groupingBy { it }.eachCount().map { (n, c) -> if (c > 1) "$n ×$c" else n }
                val closed = m.potmClosesAt?.isAfter(now) == false
                PublicResult(m.kickoffAt.atZone(CLUB_ZONE).toLocalDate(), m.opponent, m.side, m.ourScore?.toInt() ?: 0, m.theirScore?.toInt() ?: 0,
                    scorers, if (closed) winners(voteRows[m.id].orEmpty()).mapNotNull { people[it] } else emptyList())
            },
        )
    }

    private fun winners(rows: List<PotmVote>): Set<UUID> {
        val counts = rows.groupingBy { it.nomineeId }.eachCount()
        val top = counts.values.maxOrNull() ?: return emptySet()
        return counts.filterValues { it == top }.keys
    }
}

@RestController
@RequestMapping("/api/matches")
class MatchController(
    private val service: MatchService,
    private val selection: SelectionService,
    private val records: MatchRecords,
) {
    @GetMapping("/options")
    fun options() = service.options()

    @GetMapping
    fun list(@RequestParam(defaultValue = "upcoming") `when`: String, @AuthenticationPrincipal me: CurrentMember) =
        service.list(`when` == "past", me)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun create(@RequestBody req: MatchRequest, @AuthenticationPrincipal me: CurrentMember) = service.create(req, me.id)

    @GetMapping("/me/record")
    fun myRecord(@AuthenticationPrincipal me: CurrentMember) = records.forMember(me.id)

    @GetMapping("/record/{memberId}")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun record(@PathVariable memberId: UUID) = records.forMember(memberId)

    @GetMapping("/{id}")
    fun detail(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.detail(id, me)

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun update(@PathVariable id: UUID, @RequestBody req: MatchRequest, @AuthenticationPrincipal me: CurrentMember) = service.update(id, req, me.id)

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun cancel(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.cancel(id, me.id)

    // --- availability ---

    @PutMapping("/{id}/availability")
    fun myAvailability(@PathVariable id: UUID, @RequestBody req: AvailabilityRequest, @AuthenticationPrincipal me: CurrentMember) =
        service.setMyAvailability(id, req, me)

    @GetMapping("/{id}/squad")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun squad(@PathVariable id: UUID) = service.squad(id)

    @PutMapping("/{id}/availability/{memberId}")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun availabilityFor(
        @PathVariable id: UUID,
        @PathVariable memberId: UUID,
        @RequestBody req: AvailabilityRequest,
        @AuthenticationPrincipal me: CurrentMember,
    ) = service.setAvailabilityFor(id, memberId, req, me.id)

    // --- selection and lineup (coach, admin; captain can look) ---

    @GetMapping("/{id}/selection")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun selection(@PathVariable id: UUID, @RequestParam(required = false) formation: String?) = selection.selection(id, formation)

    @PutMapping("/{id}/weights")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun weights(@PathVariable id: UUID, @RequestBody req: WeightsRequest) = selection.setWeights(id, req)

    @PutMapping("/{id}/lineup")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun lineup(@PathVariable id: UUID, @RequestBody req: LineupRequest, @AuthenticationPrincipal me: CurrentMember) = service.saveLineup(id, req, me.id)

    @PostMapping("/{id}/lineup/publish")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun publish(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.publishLineup(id, me.id)

    // --- after the game ---

    @PutMapping("/{id}/result")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun result(@PathVariable id: UUID, @RequestBody req: ResultRequest, @AuthenticationPrincipal me: CurrentMember) = service.saveResult(id, req, me.id)

    @PostMapping("/{id}/potm/vote")
    fun vote(@PathVariable id: UUID, @RequestBody req: VoteRequest, @AuthenticationPrincipal me: CurrentMember) = service.vote(id, req, me)

    @PostMapping("/{id}/potm/close")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun closeVote(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.closeVote(id, me.id)

    @PutMapping("/{id}/opinion")
    fun opinion(@PathVariable id: UUID, @RequestBody req: OpinionRequest, @AuthenticationPrincipal me: CurrentMember) = service.saveOpinion(id, req, me)
}

@RestController
class PublicMatchController(private val records: MatchRecords) {
    @GetMapping("/api/public/matches")
    fun matches() = records.publicMatches()
}
