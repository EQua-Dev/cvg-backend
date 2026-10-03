package ng.cvgfc.api.style

import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.match.Formations
import ng.cvgfc.api.match.GamePlan
import ng.cvgfc.api.profile.PositionGroup
import ng.cvgfc.api.profiling.RoleResult
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ChemistrySlot(val idx: Int, val memberId: UUID?)

data class ChemistryRequest(val teamSize: Int, val formation: String, val plan: GamePlan? = null, val slots: List<ChemistrySlot>)

data class ChemistryLinkView(val a: Int, val b: Int, val link: Link, val scope: RuleScope, val note: String?)

data class ChemistryView(
    val links: List<ChemistryLinkView>,
    /** Green links − red links + players who fit the plan (70+). */
    val total: Int,
    val green: Int,
    val amber: Int,
    val red: Int,
    /** Slots whose player fits the selected plan (70+). */
    val planFits: List<Int>,
    /** Role in each filled slot. */
    val roles: Map<Int, RoleResult?>,
)

data class RuleView(val id: UUID, val roleA: String, val roleB: String, val nameA: String, val nameB: String, val link: Link, val scope: RuleScope, val plan: String?, val unlessRole: String?, val note: String?)

data class RuleRequest(val roleA: String, val roleB: String, val link: Link, val scope: RuleScope = RuleScope.NEIGHBOURS, val plan: String? = null, val unlessRole: String? = null, val note: String? = null)

/** Which pairings work together on the pitch, from the coach's rules. */
@Service
class ChemistryService(
    private val rules: ChemistryRuleRepository,
    private val styles: StyleService,
    private val properties: CvgProperties,
) {
    @Transactional(readOnly = true)
    fun evaluate(req: ChemistryRequest): ChemistryView {
        val formation = Formations.find(req.teamSize, req.formation) ?: throw ApiException.badRequest("invalid_formation", "Pick a formation.")
        val filled = req.slots.filter { it.memberId != null && formation.slots.any { s -> s.idx == it.idx } }.associate { it.idx to it.memberId!! }
        val roles = styles.roles(filled.values.toSet())
        val roleAt = filled.mapValues { (_, m) -> roles[m] }
        val all = rules.findByClubIdOrderByCreatedAt(properties.clubId).filter { it.plan == null || it.plan == req.plan?.name }

        val links = mutableListOf<ChemistryLinkView>()
        // Neighbours: one link per pair, the most serious rule wins.
        for ((a, b) in Formations.neighbours(formation)) {
            val ra = roleAt[a]?.code ?: continue
            val rb = roleAt[b]?.code ?: continue
            all.filter { it.scope == RuleScope.NEIGHBOURS && pairMatches(it, ra, rb) }
                .maxByOrNull { severity(it.link) }
                ?.let { links += ChemistryLinkView(a, b, it.link, it.scope, it.note) }
        }
        // Team rules: anywhere in the XI, unless the cancelling role is present.
        val present = roleAt.mapNotNull { (idx, r) -> r?.code?.let { idx to it } }
        for (rule in all.filter { it.scope == RuleScope.TEAM }) {
            val unless = rule.unlessRole
            if (unless != null && present.any { matches(unless, it.second) }) continue
            val pair = present.flatMap { x -> present.filter { it.first > x.first }.map { x to it } }
                .firstOrNull { (x, y) -> pairMatches(rule, x.second, y.second) } ?: continue
            links += ChemistryLinkView(pair.first.first, pair.second.first, rule.link, rule.scope, rule.note)
        }

        val fits = if (req.plan == null) emptyList() else {
            val planFits = styles.planFits(filled.values.toSet())
            filled.filter { (_, m) -> (planFits[m]?.get(req.plan.name) ?: 0) >= PLAN_FIT_BONUS }.keys.sorted()
        }
        val green = links.count { it.link == Link.GREEN }
        val red = links.count { it.link == Link.RED }
        return ChemistryView(links, green - red + fits.size, green, links.count { it.link == Link.AMBER }, red, fits, roleAt)
    }

    @Transactional(readOnly = true)
    fun rules(): List<RuleView> = rules.findByClubIdOrderByCreatedAt(properties.clubId).map(::view)

    @Transactional
    fun addRule(req: RuleRequest): RuleView {
        for (token in listOfNotNull(req.roleA, req.roleB, req.unlessRole)) {
            if (!validToken(token)) throw ApiException.badRequest("invalid_role", "Unknown role $token.")
        }
        if (req.plan != null && GamePlan.parse(req.plan) == null) throw ApiException.badRequest("invalid_plan", "Unknown game plan.")
        return view(rules.save(ChemistryRule(properties.clubId, req.roleA, req.roleB, req.link, req.scope, req.plan, req.unlessRole, req.note?.trim()?.take(120))))
    }

    @Transactional
    fun deleteRule(id: UUID) {
        rules.delete(rules.findByIdAndClubId(id, properties.clubId) ?: throw ApiException.notFound("Rule"))
    }

    private fun view(r: ChemistryRule) = RuleView(r.id, r.roleA, r.roleB, tokenName(r.roleA), tokenName(r.roleB), r.link, r.scope, r.plan, r.unlessRole, r.note)

    private fun tokenName(t: String) = if (t.endsWith("*")) "Any ${GROUP_NAMES[t.dropLast(1)] ?: t}" else styles.roleName(t) ?: t

    private fun validToken(t: String) = if (t.endsWith("*")) t.dropLast(1) in GROUP_NAMES else styles.roleName(t) != null

    /** A role code, or a whole group like "DEF*". */
    private fun matches(token: String, role: String): Boolean =
        if (token.endsWith("*")) groupOf(role)?.name == token.dropLast(1) else token == role

    private fun pairMatches(r: ChemistryRule, x: String, y: String) =
        (matches(r.roleA, x) && matches(r.roleB, y)) || (matches(r.roleA, y) && matches(r.roleB, x))

    private fun groupOf(role: String): PositionGroup? =
        PositionGroup.entries.firstOrNull { g -> styles.rolesFor(g).any { it.code == role } }

    private fun severity(l: Link) = when (l) { Link.RED -> 3; Link.AMBER -> 2; Link.GREEN -> 1 }

    companion object {
        const val PLAN_FIT_BONUS = 70
        private val GROUP_NAMES = mapOf("GK" to "keeper", "DEF" to "defender", "MID" to "midfielder", "ATT" to "attacker")
    }
}

@RestController
class StyleController(private val styles: StyleService, private val chemistry: ChemistryService) {

    @GetMapping("/api/styles")
    fun squad(@AuthenticationPrincipal me: CurrentMember) = styles.squad(me)

    @GetMapping("/api/styles/me")
    fun mine(@AuthenticationPrincipal me: CurrentMember) = styles.mine(me.id)

    @GetMapping("/api/styles/{memberId}")
    fun of(@PathVariable memberId: UUID, @AuthenticationPrincipal me: CurrentMember) = styles.of(memberId, me)

    @PutMapping("/api/styles/{memberId}/role")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun setRole(@PathVariable memberId: UUID, @RequestBody req: RoleRequest, @AuthenticationPrincipal me: CurrentMember) =
        styles.setRole(memberId, req, me.id)

    @GetMapping("/api/styles/roles/{group}")
    fun roles(@PathVariable group: PositionGroup) = styles.rolesFor(group)

    /** Asks everyone to redo the questionnaire (e.g. a new season). */
    @PostMapping("/api/styles/rounds")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun openRound(@AuthenticationPrincipal me: CurrentMember) = mapOf("openedAt" to styles.openRound(me.id))

    @GetMapping("/api/ratings/me/{memberId}/role")
    fun roleSheet(@PathVariable memberId: UUID, @AuthenticationPrincipal me: CurrentMember) = styles.roleSheet(me.id, memberId)

    @PutMapping("/api/ratings/me/{memberId}/role")
    fun voteRole(@PathVariable memberId: UUID, @RequestBody req: RoleRequest, @AuthenticationPrincipal me: CurrentMember) =
        styles.voteRole(me.id, memberId, req)

    @PostMapping("/api/chemistry")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun evaluate(@RequestBody req: ChemistryRequest) = chemistry.evaluate(req)

    @GetMapping("/api/chemistry/rules")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun rules() = chemistry.rules()

    @PostMapping("/api/chemistry/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun addRule(@RequestBody req: RuleRequest) = chemistry.addRule(req)

    @DeleteMapping("/api/chemistry/rules/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun deleteRule(@PathVariable id: UUID): ResponseEntity<Void> {
        chemistry.deleteRule(id)
        return ResponseEntity.noContent().build()
    }
}
