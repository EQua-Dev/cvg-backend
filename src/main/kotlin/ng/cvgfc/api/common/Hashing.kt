package ng.cvgfc.api.common

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

object Hashing {

    private val random = SecureRandom()

    fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    /** URL-safe random token with [bytes] bytes of entropy. */
    fun randomToken(bytes: Int = 32): String {
        val buf = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    /** Zero-padded numeric code, e.g. "042917". */
    fun randomDigits(length: Int): String = buildString { repeat(length) { append(random.nextInt(10)) } }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
