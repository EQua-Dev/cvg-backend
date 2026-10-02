package ng.cvgfc.api.auth

import ng.cvgfc.api.audit.AuditService
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.common.Hashing
import ng.cvgfc.api.common.PhoneNumbers
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class SignedIn(val token: String, val member: Member)

@Service
class AuthService(
    private val members: MemberRepository,
    private val sessions: AuthSessionRepository,
    private val passcodes: Passcodes,
    private val audit: AuditService,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    /**
     * Phone + passcode sign-in. Unknown numbers get a clear "not on the register":
     * the squad is small and a helpful message matters more than hiding membership.
     */
    @Transactional(noRollbackFor = [ApiException::class])
    fun signIn(rawPhone: String, passcode: String, userAgent: String?): SignedIn {
        val phone = PhoneNumbers.require(rawPhone)
        val member = members.findByClubIdAndPhone(properties.clubId, phone)
            ?.takeIf { it.status.canSignIn }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "not_registered", "This number is not on the register.")

        passcodes.verify(member, passcode)
        return SignedIn(startSession(member, userAgent), member)
    }

    /** Creates a session for a member who has just proven who they are. Returns the raw token. */
    @Transactional
    fun startSession(member: Member, userAgent: String?): String {
        val now = Instant.now(clock)
        val token = Hashing.randomToken(32)
        sessions.save(
            AuthSession(member.id, Hashing.sha256(token), now.plus(properties.auth.sessionTtl), userAgent?.take(255), now),
        )
        return token
    }

    /** The member sets their own passcode. Other devices are signed out. */
    @Transactional(noRollbackFor = [ApiException::class])
    fun changePasscode(me: CurrentMember, currentPasscode: String, newPasscode: String) {
        val member = members.findById(me.id).orElseThrow { ApiException.notFound("Member") }
        passcodes.verify(member, currentPasscode)
        passcodes.set(member, newPasscode)
        revokeSessions(member.id, keep = me.sessionId)
        audit.record(member.id, "member.passcode_changed", "member", member.id, "${member.fullName} set a passcode")
    }

    /** Admin reset: back to the last 4 digits of the phone, unlocked, all devices signed out. */
    @Transactional
    fun resetPasscode(member: Member, actorId: UUID) {
        passcodes.reset(member)
        revokeSessions(member.id, keep = null)
        audit.record(actorId, "member.passcode_reset", "member", member.id, "Passcode reset for ${member.fullName}")
    }

    /** Resolves a raw session token to the signed-in member, or null if invalid. */
    @Transactional
    fun authenticate(token: String): CurrentMember? {
        val now = Instant.now(clock)
        val session = sessions.findByTokenHash(Hashing.sha256(token))?.takeIf { it.isLive(now) } ?: return null
        val member = members.findById(session.memberId).orElse(null)?.takeIf { it.status.canSignIn } ?: return null
        // Touch at most hourly to avoid a write on every request.
        if (session.lastSeenAt?.isBefore(now.minus(Duration.ofHours(1))) != false) {
            session.lastSeenAt = now
        }
        return CurrentMember(member.id, session.id, member.roles.toSet())
    }

    @Transactional
    fun signOut(sessionId: UUID) {
        sessions.findById(sessionId).ifPresent { it.revokedAt = Instant.now(clock) }
    }

    private fun revokeSessions(memberId: UUID, keep: UUID?) {
        val now = Instant.now(clock)
        sessions.findByMemberIdAndRevokedAtIsNull(memberId)
            .filter { it.id != keep }
            .forEach { it.revokedAt = now }
    }
}
