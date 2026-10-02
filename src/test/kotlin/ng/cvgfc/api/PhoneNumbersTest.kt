package ng.cvgfc.api

import ng.cvgfc.api.common.PhoneNumbers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneNumbersTest {

    @Test
    fun `normalises common Nigerian formats`() {
        listOf("0803 555 0192", "08035550192", "2348035550192", "+234 803 555 0192", "8035550192")
            .forEach { assertEquals("+2348035550192", PhoneNumbers.normalise(it), it) }
    }

    @Test
    fun `rejects non-mobile and malformed numbers`() {
        listOf("0103 555 0192", "12345", "", "080355501921", "+44 7700 900123")
            .forEach { assertNull(PhoneNumbers.normalise(it), it) }
    }

    @Test
    fun `formats for display`() {
        assertEquals("0803 555 0192", PhoneNumbers.display("+2348035550192"))
    }
}
