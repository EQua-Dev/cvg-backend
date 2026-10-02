package ng.cvgfc.api.auth

import ng.cvgfc.api.auth.sms.SmsSender
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

data class SignedIn(val token: String, val member: Member)

@Service
class AuthService(
    private val members: MemberRepository,
    private val challenges: OtpChallengeRepository,
    private val sessions: AuthSessionRepository,
    private val sms: SmsSender,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    private val config get() = properties.auth

    /**
     * Sends a 6-digit code to a registered member. Unknown numbers get a clear
     * "not on the register" answer: the squad is small and the UX matters more
     * than hiding who is a member. Requests are rate-limited per phone.
     */
    @Transactional
    fun requestCode(rawPhone: String): Duration {
        val phone = PhoneNumbers.require(rawPhone)
        val member = members.findByClubIdAndPhone(properties.clubId, phone)
        if (member == null || !member.status.canSignIn) {
            throw ApiException(HttpStatus.NOT_FOUND, "not_registered", "This number is not on the register.")
        }
        val now = Instant.now(clock)
        if (challenges.countByPhoneAndCreatedAtAfter(phone, now.minus(Duration.ofHours(1))) >= config.otpMaxRequestsPerHour) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_codes", "Too many codes. Try again later.")
        }
        val code = Hashing.randomDigits(6)
        challenges.save(OtpChallenge(phone, hashCode(phone, code), now.plus(config.otpTtl), now))
        sms.send(phone, "$code is your CVG FC code. It expires in ${config.otpTtl.toMinutes()} mins.")
        return config.otpTtl
    }

    /** Checks the latest code for this phone. Failed attempts are saved even though we throw. */
    @Transactional(noRollbackFor = [ApiException::class])
    fun verifyCode(rawPhone: String, code: String, userAgent: String?): SignedIn {
        val phone = PhoneNumbers.require(rawPhone)
        val now = Instant.now(clock)
        val challenge = challenges.findFirstByPhoneOrderByCreatedAtDesc(phone)
            ?.takeIf { it.consumedAt == null && it.expiresAt.isAfter(now) }
            ?: throw ApiException.badRequest("code_expired", "Code expired. Get a new one.")

        if (challenge.attempts >= config.otpMaxAttempts) {
            throw ApiException.badRequest("too_many_attempts", "Too many tries. Get a new code.")
        }
        if (!Hashing.constantTimeEquals(challenge.codeHash, hashCode(phone, code.trim()))) {
            challenge.attempts = (challenge.attempts + 1).toShort()
            throw ApiException.badRequest("wrong_code", "Wrong code.")
        }
        challenge.consumedAt = now

        val member = members.findByClubIdAndPhone(properties.clubId, phone)
            ?.takeIf { it.status.canSignIn }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "not_registered", "This number is not on the register.")

        val token = Hashing.randomToken(32)
        sessions.save(AuthSession(member.id, Hashing.sha256(token), now.plus(config.sessionTtl), userAgent?.take(255), now))
        return SignedIn(token, member)
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
    fun signOut(sessionId: java.util.UUID) {
        sessions.findById(sessionId).ifPresent { it.revokedAt = Instant.now(clock) }
    }

    private fun hashCode(phone: String, code: String) = Hashing.sha256("$phone:$code")
}
