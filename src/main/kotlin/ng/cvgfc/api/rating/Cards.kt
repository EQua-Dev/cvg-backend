package ng.cvgfc.api.rating

import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.match.MatchService
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.profile.MemberProfile
import ng.cvgfc.api.profile.MemberProfileRepository
import ng.cvgfc.api.profile.Position
import ng.cvgfc.api.profile.PositionGroup
import ng.cvgfc.api.profile.ProfileService
import ng.cvgfc.api.profile.photoUrl
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class CardStat(
    val code: String,
    val label: String,
    val title: String,
    val value: Int?,
    val peers: Int,
    /** The player's own rating; only in their own view. */
    val self: Int?,
)

data class GroupCard(val group: PositionGroup, val ovr: Int?, val tier: Tier?, val published: Boolean, val stats: List<CardStat>)

data class CardView(
    val memberId: UUID,
    val name: String,
    val fullName: String,
    val jerseyNumber: Short?,
    val photoUrl: String?,
    val stateOfOrigin: String?,
    val position: String?,
    val group: PositionGroup?,
    val ovr: Int?,
    val tier: Tier?,
    val published: Boolean,
    /** The favoured group's 6 stats. */
    val stats: List<CardStat>,
    /** Set when another group rates higher than the favoured one. */
    val bestGroup: PositionGroup?,
    /** Cards for every group, for alternate positions. */
    val groups: List<GroupCard>,
    /** Groups of the player's other positions (for alternate cards). */
    val otherGroups: List<PositionGroup>,
    val round: String,
    val windowId: UUID,
    val createdAt: Instant,
)

data class CardHistory(val windowId: UUID, val round: String, val ovr: Int?, val tier: Tier?, val createdAt: Instant)

data class MyCards(val latest: CardView?, val history: List<CardHistory>)

data class StatSetView(val group: PositionGroup, val attrs: List<String>, val labels: List<String>)

data class AttributeView(val code: String, val label: String, val title: String, val block: Block)

data class StatSetsView(val sets: List<StatSetView>, val attributes: List<AttributeView>)

data class StatSetRequest(val attrs: List<String>)

data class PublicCard(
    val name: String,
    val position: String?,
    val ovr: Int,
    val tier: Tier,
    val stats: List<Map<String, Any>>,
    val photoUrl: String?,
    val round: String,
)

@Service
class CardService(
    private val cards: PlayerCardRepository,
    private val windows: RatingWindowRepository,
    private val sets: StatSetRepository,
    private val members: MemberRepository,
    private val profiles: MemberProfileRepository,
    private val profileService: ProfileService,
    private val properties: CvgProperties,
) {
    private val clubId get() = properties.clubId

    @Transactional(readOnly = true)
    fun statSets(): Map<PositionGroup, List<String>> =
        sets.findByIdClubId(clubId).associate { PositionGroup.valueOf(it.id.group) to it.attrs }

    @Transactional(readOnly = true)
    fun statSetsView() = StatSetsView(
        statSets().entries.sortedBy { it.key.ordinal }.map { (g, a) -> StatSetView(g, a, a.map { Attribute.valueOf(it).label }) },
        Attribute.entries.map { AttributeView(it.name, it.label, it.title, it.block) },
    )

    /** Swapping a card stat needs no re-vote: everything was rated. */
    @Transactional
    fun setStatSet(group: PositionGroup, req: StatSetRequest): StatSetsView {
        val attrs = req.attrs.distinct()
        if (attrs.size != 6) throw ApiException.badRequest("six_stats", "Pick exactly 6.")
        if (attrs.any { Attribute.parse(it) == null }) throw ApiException.badRequest("invalid_attribute", "Unknown attribute.")
        val row = sets.findById(StatSetId(clubId, group.name)).orElse(null)
        if (row == null) sets.save(StatSet(StatSetId(clubId, group.name), attrs)) else row.apply { this.attrs = attrs; updatedAt = Instant.now() }
        sets.flush()
        return statSetsView()
    }

    @Transactional(readOnly = true)
    fun mine(memberId: UUID): MyCards {
        val list = cards.findByMemberIdOrderByCreatedAtDesc(memberId)
        if (list.isEmpty()) return MyCards(null, emptyList())
        val member = members.findById(memberId).orElseThrow()
        val profile = profiles.findById(memberId).orElse(null)
        val titles = titles(list.map { it.windowId })
        val s = statSets()
        val views = list.map { view(it, member, profile, s, titles, includeSelf = true) }
        return MyCards(views.first(), views.map { CardHistory(it.windowId, it.round, it.ovr, it.tier, it.createdAt) })
    }

    /** The squad's latest cards. Unpublished ones come back without stats. */
    @Transactional(readOnly = true)
    fun squad(): List<CardView> {
        val people = members.findByClubIdOrderByMemberNo(clubId).filter { it.status.isCurrent }.associateBy { it.id }
        val latest = latestByMember(people.keys)
        val profileMap = profiles.findAllById(latest.keys).associateBy { it.memberId }
        val titles = titles(latest.values.map { it.windowId })
        val s = statSets()
        return latest.values.map { view(it, people.getValue(it.memberId), profileMap[it.memberId], s, titles, includeSelf = false) }
            .map { if (it.published) it else it.copy(stats = emptyList(), groups = emptyList(), ovr = null, tier = null) }
            .sortedWith(compareByDescending<CardView> { it.ovr ?: -1 }.thenBy { it.name })
    }

    @Transactional(readOnly = true)
    fun ofMember(memberId: UUID): CardView? {
        val member = members.findByIdAndClubId(memberId, clubId) ?: throw ApiException.notFound("Member")
        val card = cards.findByMemberIdOrderByCreatedAtDesc(memberId).firstOrNull() ?: return null
        return view(card, member, profiles.findById(memberId).orElse(null), statSets(), titles(listOf(card.windowId)), includeSelf = false)
    }

    /** Group OVRs from each player's latest card, for selection. */
    @Transactional(readOnly = true)
    fun groupOvrs(memberIds: Collection<UUID>): Map<UUID, Map<PositionGroup, Int?>> {
        val s = statSets()
        return latestByMember(memberIds).mapValues { (_, c) -> c.groupOvrs(s) }
    }

    /** Public site: published cards of members who agreed to be shown. */
    @Transactional(readOnly = true)
    fun publicSquad(): List<PublicCard> {
        val people = members.findByClubIdOrderByMemberNo(clubId).filter { it.status == MemberStatus.ACTIVE }.associateBy { it.id }
        val profileMap = profiles.findAllById(people.keys).associateBy { it.memberId }
        val consenting = people.keys.filter { profileMap[it]?.showsPublicly == true }
        val latest = latestByMember(consenting)
        val titles = titles(latest.values.map { it.windowId })
        val s = statSets()
        return latest.values.map { view(it, people.getValue(it.memberId), profileMap[it.memberId], s, titles, includeSelf = false) }
            .filter { it.published && it.ovr != null }
            .sortedByDescending { it.ovr }
            .map { c ->
                PublicCard(
                    name = c.name,
                    position = c.position,
                    ovr = c.ovr!!,
                    tier = c.tier!!,
                    stats = c.stats.map { mapOf("label" to it.label, "value" to (it.value ?: 0)) },
                    photoUrl = profileMap[c.memberId]?.photoId?.let { "/api/public/squad/${c.memberId}/photo" },
                    round = c.round,
                )
            }
    }

    @Transactional(readOnly = true)
    fun publicPhoto(memberId: UUID): ng.cvgfc.api.profile.Photo? {
        val m = members.findByIdAndClubId(memberId, clubId) ?: return null
        if (m.status != MemberStatus.ACTIVE || profiles.findById(memberId).orElse(null)?.showsPublicly != true) return null
        return profileService.photo(memberId)
    }

    private fun latestByMember(ids: Collection<UUID>): Map<UUID, PlayerCard> =
        cards.findByMemberIdIn(ids).groupBy { it.memberId }.mapValues { (_, l) -> l.maxBy { it.createdAt } }

    private fun titles(windowIds: Collection<UUID>) = windows.findAllById(windowIds.toSet()).associate { it.id to it.title }

    private fun view(c: PlayerCard, m: Member, p: MemberProfile?, sets: Map<PositionGroup, List<String>>, titles: Map<UUID, String>, includeSelf: Boolean): CardView {
        val position = p?.favouredPosition ?: Position.parse(c.position)
        val favoured = position?.group
        fun stat(code: String): CardStat {
            val a = Attribute.valueOf(code)
            return CardStat(code, a.label, a.title, c.stats[code], c.peerCounts[code] ?: 0, if (includeSelf) c.selfStats[code] else null)
        }
        val groups = PositionGroup.entries.map { g ->
            val stats = sets[g].orEmpty().map(::stat)
            val ovr = CardMath.ovr(stats.map { it.value })
            val published = ovr != null && stats.all { it.peers >= CardMath.MIN_PEERS_TO_PUBLISH }
            GroupCard(g, ovr, ovr?.let(CardMath::tier), published, stats)
        }
        val main = groups.firstOrNull { it.group == favoured }
        val best = groups.filter { it.published && it.ovr != null }.maxByOrNull { it.ovr!! }
        val others = p?.otherPositions.orEmpty().mapNotNull { Position.parse(it)?.group }.distinct().filter { it != favoured }
        return CardView(
            memberId = m.id,
            name = MatchService.shortName(m),
            fullName = m.fullName,
            jerseyNumber = m.jerseyNumber,
            photoUrl = p?.photoId?.let { photoUrl(m.id, it) },
            stateOfOrigin = p?.stateOfOrigin,
            position = position?.name,
            group = favoured,
            ovr = main?.ovr,
            tier = main?.tier,
            published = main?.published == true,
            stats = main?.stats.orEmpty(),
            bestGroup = best?.group?.takeIf { main?.published == true && it != favoured && best.ovr!! > (main.ovr ?: 0) },
            groups = groups,
            otherGroups = others,
            round = titles[c.windowId] ?: "",
            windowId = c.windowId,
            createdAt = c.createdAt,
        )
    }
}

@RestController
@RequestMapping("/api/ratings")
class RatingController(private val service: RatingService, private val cardService: CardService) {

    @GetMapping("/windows")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun windows() = service.list()

    @PostMapping("/windows")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun open(@RequestBody req: OpenWindowRequest, @AuthenticationPrincipal me: CurrentMember) = service.open(req, me.id)

    @PostMapping("/windows/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun close(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.closeNow(id, me.id)

    @GetMapping("/stat-sets")
    fun statSets() = cardService.statSetsView()

    @PutMapping("/stat-sets/{group}")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun setStatSet(@PathVariable group: PositionGroup, @RequestBody req: StatSetRequest) = cardService.setStatSet(group, req)

    /** What I have to rate in the open window; 204 when none is open. */
    @GetMapping("/me")
    fun mine(@AuthenticationPrincipal me: CurrentMember): ResponseEntity<MyRating> =
        service.mine(me.id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @GetMapping("/me/{memberId}")
    fun sheet(@PathVariable memberId: UUID, @AuthenticationPrincipal me: CurrentMember) = service.sheet(me.id, memberId)

    @PutMapping("/me/{memberId}")
    fun save(@PathVariable memberId: UUID, @RequestBody req: ScoresRequest, @AuthenticationPrincipal me: CurrentMember) =
        service.save(me.id, memberId, req)
}

@RestController
class CardController(private val service: CardService) {

    @GetMapping("/api/cards/me")
    fun mine(@AuthenticationPrincipal me: CurrentMember) = service.mine(me.id)

    @GetMapping("/api/cards")
    fun squad() = service.squad()

    @GetMapping("/api/cards/{memberId}")
    fun ofMember(@PathVariable memberId: UUID): ResponseEntity<CardView> =
        service.ofMember(memberId)?.let { c -> ResponseEntity.ok(if (c.published) c else c.copy(stats = emptyList(), groups = emptyList(), ovr = null, tier = null)) }
            ?: ResponseEntity.noContent().build()

    @GetMapping("/api/public/squad")
    fun publicSquad() = service.publicSquad()

    @GetMapping("/api/public/squad/{memberId}/photo")
    fun publicPhoto(@PathVariable memberId: UUID): ResponseEntity<ByteArray> {
        val photo = service.publicPhoto(memberId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic()).body(photo.data)
    }
}

/** Closes rating windows when their end date passes. */
@Component
class RatingWindowJob(private val service: RatingService) {
    @Scheduled(cron = "0 */15 * * * *")
    fun run() {
        service.closeExpired()
    }
}
