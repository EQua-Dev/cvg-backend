package ng.cvgfc.api.common

/**
 * Normalises Nigerian mobile numbers to E.164 (+234XXXXXXXXXX).
 * Accepts "0803 555 0192", "08035550192", "2348035550192", "+234 803 555 0192".
 */
object PhoneNumbers {

    fun normalise(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() } ?: return null
        val national = when {
            digits.startsWith("234") && digits.length == 13 -> digits.substring(3)
            digits.startsWith("0") && digits.length == 11 -> digits.substring(1)
            digits.length == 10 && !digits.startsWith("0") -> digits
            else -> return null
        }
        // Nigerian mobile numbers start with 7, 8 or 9 after the leading 0.
        if (national[0] < '7') return null
        return "+234$national"
    }

    fun require(raw: String?): String =
        normalise(raw) ?: throw ApiException.badRequest("invalid_phone", "Enter a valid phone number.")

    /** "+2348035550192" → "0803 555 0192" for display. */
    fun display(e164: String): String {
        if (!e164.startsWith("+234") || e164.length != 14) return e164
        val n = "0" + e164.substring(4)
        return "${n.substring(0, 4)} ${n.substring(4, 7)} ${n.substring(7)}"
    }
}
