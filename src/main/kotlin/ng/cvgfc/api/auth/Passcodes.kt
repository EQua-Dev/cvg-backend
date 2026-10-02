package ng.cvgfc.api.auth

import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.common.Hashing
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.Member
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * Passcode rules and checking. A member with no passcode of their own signs in
 * with the last 4 digits of their phone. Wrong attempts lock the account for a while.
 */
@Component
class Passcodes(
    private val encoder: PasswordEncoder,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    private val config get() = properties.auth

    /**
     * Checks [input] against the member's passcode, counting failures on the member.
     * Callers must not roll back on [ApiException], so failed attempts are saved.
     */
    fun verify(member: Member, input: String) {
        val now = Instant.now(clock)
        member.passcodeLockedUntil?.let {
            if (it.isAfter(now)) throw locked()
            member.passcodeLockedUntil = null
        }
        if (matches(member, input.trim())) {
            member.failedPasscodeAttempts = 0
            return
        }
        val attempts = member.failedPasscodeAttempts + 1
        if (attempts >= config.passcodeMaxAttempts) {
            member.failedPasscodeAttempts = 0
            member.passcodeLockedUntil = now.plus(config.passcodeLockout)
            throw locked()
        }
        member.failedPasscodeAttempts = attempts.toShort()
        throw ApiException.badRequest("wrong_passcode", "Wrong passcode.")
    }

    /** Validates and stores a new passcode chosen by the member. */
    fun set(member: Member, newPasscode: String) {
        val code = newPasscode.trim()
        if (!code.matches(Regex("\\d{4,6}"))) {
            throw ApiException.badRequest("invalid_passcode", "Use 4 to 6 numbers.")
        }
        if (isTooEasy(code)) {
            throw ApiException.badRequest("weak_passcode", "Too easy to guess. Try another.")
        }
        member.passcodeHash = encoder.encode(code)
        member.failedPasscodeAttempts = 0
        member.passcodeLockedUntil = null
    }

    /** Back to the default (last 4 digits of phone) and unlocked. */
    fun reset(member: Member) {
        member.passcodeHash = null
        member.failedPasscodeAttempts = 0
        member.passcodeLockedUntil = null
    }

    private fun matches(member: Member, input: String): Boolean {
        val hash = member.passcodeHash ?: return Hashing.constantTimeEquals(member.defaultPasscode, input)
        return encoder.matches(input, hash)
    }

    private fun locked() = ApiException(
        HttpStatus.TOO_MANY_REQUESTS, "passcode_locked",
        "Too many tries. Wait ${config.passcodeLockout.toMinutes()} mins or ask an admin.",
    )

    companion object {
        /** All the same digit (0000) or a straight run up or down (1234, 9876). */
        fun isTooEasy(code: String): Boolean {
            if (code.all { it == code[0] }) return true
            val steps = code.zipWithNext { a, b -> b - a }.toSet()
            return steps == setOf(1) || steps == setOf(-1)
        }
    }
}
