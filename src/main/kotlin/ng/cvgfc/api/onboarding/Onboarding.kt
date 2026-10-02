package ng.cvgfc.api.onboarding

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import ng.cvgfc.api.audit.AuditService
import ng.cvgfc.api.auth.AuthService
import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.auth.Passcodes
import ng.cvgfc.api.auth.sessionCookie
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.common.Hashing
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberService
import ng.cvgfc.api.member.MemberView
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "onboarding_link")
class OnboardingLink(
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: UUID,
    @Column(name = "token_hash", nullable = false, updatable = false)
    val tokenHash: String,
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
    @Column(name = "created_by", updatable = false)
    val createdBy: UUID?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "used_at")
    var usedAt: Instant? = null

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    fun isOpen(now: Instant) = usedAt == null && revokedAt == null && expiresAt.isAfter(now)
}

interface OnboardingLinkRepository : JpaRepository<OnboardingLink, UUID> {
    fun findByTokenHash(tokenHash: String): OnboardingLink?
    fun findByMemberIdAndUsedAtIsNullAndRevokedAtIsNull(memberId: UUID): List<OnboardingLink>
}

data class CreatedLink(val url: String, val expiresAt: Instant)

/** What the join page shows before the member has signed in. Deliberately minimal. */
data class JoinView(
    val firstName: String,
    val code: String,
    val jerseyNumber: Short?,
    val phoneHint: String,
    /** Already chose their own passcode: they should just sign in. */
    val alreadySetUp: Boolean,
)

data class ClaimRequest(@field:NotBlank(message = "Pick a passcode.") val passcode: String)

data class ClaimResponse(val token: String, val member: MemberView)

@Service
class OnboardingService(
    private val links: OnboardingLinkRepository,
    private val members: MemberService,
    private val passcodes: Passcodes,
    private val auth: AuthService,
    private val audit: AuditService,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    /** New private link for a member. Any earlier unused link stops working. */
    @Transactional
    fun create(memberId: UUID, actorId: UUID): CreatedLink {
        val member = members.get(memberId)
        if (!member.status.canSignIn) {
            throw ApiException.conflict("member_left", "${member.fullName} has left the club.")
        }
        val now = Instant.now(clock)
        links.findByMemberIdAndUsedAtIsNullAndRevokedAtIsNull(memberId).forEach { it.revokedAt = now }

        val token = Hashing.randomToken(24)
        val link = links.save(
            OnboardingLink(memberId, Hashing.sha256(token), now.plus(properties.onboarding.linkTtl), actorId, now),
        )
        audit.record(actorId, "member.onboarding_link_created", "member", memberId, "Join link for ${member.fullName}")
        return CreatedLink("${properties.links.clubApp.trimEnd('/')}/join/$token", link.expiresAt)
    }

    @Transactional(readOnly = true)
    fun lookup(token: String): JoinView {
        val (_, member) = open(token)
        val digits = member.phone.takeLast(10)
        return JoinView(
            firstName = member.nickname ?: member.fullName.substringBefore(' '),
            code = member.code,
            jerseyNumber = member.jerseyNumber,
            phoneHint = "0${digits.take(3)} ••• ••${digits.takeLast(2)}",
            alreadySetUp = !member.usesDefaultPasscode,
        )
    }

    /** First visit: the member picks their passcode and is signed in. The link is then used up. */
    @Transactional(noRollbackFor = [ApiException::class])
    fun claim(token: String, passcode: String, userAgent: String?): ClaimResponse {
        val (link, member) = open(token)
        if (!member.usesDefaultPasscode) {
            throw ApiException.conflict("already_set_up", "You're already set up. Sign in with your passcode.")
        }
        passcodes.set(member, passcode)
        link.usedAt = Instant.now(clock)
        audit.record(member.id, "member.onboarded", "member", member.id, "${member.fullName} joined via link")
        return ClaimResponse(auth.startSession(member, userAgent), MemberView.of(member, includePhone = true))
    }

    private fun open(token: String): Pair<OnboardingLink, Member> {
        val gone = ApiException(HttpStatus.NOT_FOUND, "link_invalid", "This link has expired. Ask an admin for a new one.")
        val link = links.findByTokenHash(Hashing.sha256(token))?.takeIf { it.isOpen(Instant.now(clock)) } ?: throw gone
        val member = runCatching { members.get(link.memberId) }.getOrNull()?.takeIf { it.status.canSignIn } ?: throw gone
        return link to member
    }
}

@RestController
class OnboardingController(private val service: OnboardingService, private val properties: CvgProperties) {

    @PostMapping("/api/members/{id}/onboarding-link")
    @PreAuthorize("hasRole('ADMIN')")
    fun create(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.create(id, me.id)

    @GetMapping("/api/onboarding/{token}")
    fun lookup(@PathVariable token: String) = service.lookup(token)

    @PostMapping("/api/onboarding/{token}/claim")
    fun claim(
        @PathVariable token: String,
        @Valid @RequestBody req: ClaimRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ClaimResponse> {
        val res = service.claim(token, req.passcode, request.getHeader(HttpHeaders.USER_AGENT))
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(properties, res.token, properties.auth.sessionTtl).toString())
            .body(res)
    }
}
