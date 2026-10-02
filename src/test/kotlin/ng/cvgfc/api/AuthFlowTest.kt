package ng.cvgfc.api

import ng.cvgfc.api.auth.Passcodes
import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.member.Role
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthFlowTest : IntegrationTest() {

    private val phone = "0803 555 0192"

    private fun signInRequest(passcode: String, phone: String = this.phone) =
        post("/api/auth/sign-in").jsonBody("""{"phone":"$phone","passcode":"$passcode"}""")

    @Test
    fun `new member signs in with the last 4 digits of their phone`() {
        createMember("Tunde Adeyemi", phone)

        val result = mvc.perform(signInRequest("0192"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.member.fullName").value("Tunde Adeyemi"))
            .andExpect(jsonPath("$.member.code").value("CVG-0001"))
            .andExpect(jsonPath("$.usesDefaultPasscode").value(true))
            .andExpect(cookie().httpOnly("cvg_session", true))
            .andExpect(cookie().sameSite("cvg_session", "Lax"))
            .andReturn()

        val sessionCookie = result.response.getCookie("cvg_session")!!
        mvc.perform(get("/api/auth/me").cookie(sessionCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.member.phone").value("0803 555 0192"))
            .andExpect(jsonPath("$.usesDefaultPasscode").value(true))
    }

    @Test
    fun `phone can be typed in any common format`() {
        createMember("Tunde Adeyemi", phone)
        mvc.perform(signInRequest("0192", phone = "+234 803 555 0192")).andExpect(status().isOk)
        mvc.perform(signInRequest("0192", phone = "08035550192")).andExpect(status().isOk)
    }

    @Test
    fun `member sets their own passcode and the default stops working`() {
        createMember("Tunde Adeyemi", phone)
        val token = signIn(phone)

        mvc.perform(put("/api/auth/passcode").bearer(token).jsonBody("""{"currentPasscode":"0192","newPasscode":"2580"}"""))
            .andExpect(status().isNoContent)

        mvc.perform(signInRequest("0192")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("wrong_passcode"))
        mvc.perform(signInRequest("2580")).andExpect(status().isOk).andExpect(jsonPath("$.usesDefaultPasscode").value(false))

        // Stored as a hash, never in plain text.
        val stored = jdbc.queryForObject("SELECT passcode_hash FROM member", String::class.java)!!
        assertTrue(stored.startsWith("$2"), stored)
        assertFalse(stored.contains("2580"))
    }

    @Test
    fun `changing the passcode keeps this device and signs out the others`() {
        createMember("Tunde Adeyemi", phone)
        val phoneA = signIn(phone)
        val phoneB = signIn(phone)

        mvc.perform(put("/api/auth/passcode").bearer(phoneA).jsonBody("""{"currentPasscode":"0192","newPasscode":"2580"}"""))
            .andExpect(status().isNoContent)
        mvc.perform(get("/api/auth/me").bearer(phoneA)).andExpect(status().isOk)
        mvc.perform(get("/api/auth/me").bearer(phoneB)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `weak or badly shaped passcodes are refused`() {
        createMember("Tunde Adeyemi", phone)
        val token = signIn(phone)
        listOf("1234" to "weak_passcode", "0000" to "weak_passcode", "987654" to "weak_passcode",
            "12" to "invalid_passcode", "12a4" to "invalid_passcode", "1234567" to "invalid_passcode")
            .forEach { (code, error) ->
                mvc.perform(put("/api/auth/passcode").bearer(token)
                    .jsonBody("""{"currentPasscode":"0192","newPasscode":"$code"}"""))
                    .andExpect(status().isBadRequest)
                    .andExpect(jsonPath("$.code").value(error))
            }
    }

    @Test
    fun `changing the passcode needs the current one`() {
        createMember("Tunde Adeyemi", phone)
        val token = signIn(phone)
        mvc.perform(put("/api/auth/passcode").bearer(token).jsonBody("""{"currentPasscode":"9999","newPasscode":"2580"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("wrong_passcode"))
    }

    @Test
    fun `five wrong passcodes lock the account, even against the right one`() {
        createMember("Tunde Adeyemi", phone)
        repeat(4) {
            mvc.perform(signInRequest("1111")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("wrong_passcode"))
        }
        mvc.perform(signInRequest("1111")).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("passcode_locked"))
        mvc.perform(signInRequest("0192")).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `a successful sign-in clears earlier failed attempts`() {
        createMember("Tunde Adeyemi", phone)
        repeat(4) { mvc.perform(signInRequest("1111")) }
        mvc.perform(signInRequest("0192")).andExpect(status().isOk)
        repeat(4) { mvc.perform(signInRequest("1111")).andExpect(jsonPath("$.code").value("wrong_passcode")) }
    }

    @Test
    fun `admin reset brings back the default, unlocks, and signs the member out`() {
        createMember("Amaka Nwosu", "0805 555 0134", setOf(Role.ADMIN))
        val tunde = createMember("Tunde Adeyemi", phone)
        val admin = signIn("0805 555 0134")

        val tundeToken = signIn(phone)
        mvc.perform(put("/api/auth/passcode").bearer(tundeToken).jsonBody("""{"currentPasscode":"0192","newPasscode":"2580"}"""))
        repeat(5) { mvc.perform(signInRequest("1111")) } // forgot it, now locked

        mvc.perform(post("/api/members/${tunde.id}/reset-passcode").bearer(tundeToken)).andExpect(status().isForbidden)
        mvc.perform(post("/api/members/${tunde.id}/reset-passcode").bearer(admin)).andExpect(status().isNoContent)
        mvc.perform(get("/api/auth/me").bearer(tundeToken)).andExpect(status().isUnauthorized)

        mvc.perform(signInRequest("0192")).andExpect(status().isOk).andExpect(jsonPath("$.usesDefaultPasscode").value(true))
        val actions = jdbc.queryForList("SELECT action FROM audit_event ORDER BY created_at", String::class.java)
        assertTrue("member.passcode_reset" in actions)
    }

    @Test
    fun `players cannot reset passcodes`() {
        val other = createMember("Kola Bello", "0807 555 0155")
        createMember("Tunde Adeyemi", phone)
        mvc.perform(post("/api/members/${other.id}/reset-passcode").bearer(signIn(phone))).andExpect(status().isForbidden)
    }

    @Test
    fun `unknown and departed numbers are told they are not on the register`() {
        createMember("Gone Player", "0806 555 0110", status = MemberStatus.LEFT)
        mvc.perform(signInRequest("0000", phone = "0809 555 0000")).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("not_registered"))
        mvc.perform(signInRequest("0110", phone = "0806 555 0110")).andExpect(status().isNotFound)
    }

    @Test
    fun `cookie-authenticated writes need the client header`() {
        createMember("Tunde Adeyemi", phone)
        val sessionCookie = mvc.perform(signInRequest("0192")).andReturn().response.getCookie("cvg_session")!!

        mvc.perform(post("/api/auth/sign-out").cookie(sessionCookie)).andExpect(status().isForbidden)
        mvc.perform(post("/api/auth/sign-out").cookie(sessionCookie).client()).andExpect(status().isNoContent)
        mvc.perform(get("/api/auth/me").cookie(sessionCookie)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `members who leave lose access immediately`() {
        val member = createMember("Tunde Adeyemi", phone)
        val token = signIn(phone)
        mvc.perform(get("/api/auth/me").bearer(token)).andExpect(status().isOk)

        jdbc.update("UPDATE member SET status = 'LEFT' WHERE id = ?", member.id)
        mvc.perform(get("/api/auth/me").bearer(token)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `requests without a session are rejected`() {
        val body = mvc.perform(get("/api/members")).andExpect(status().isUnauthorized).andReturn().response
        assertEquals("unauthenticated", parse(body.contentAsString)["code"].asText())
    }

    @Test
    fun `easy-passcode rule`() {
        listOf("1111", "1234", "4321", "456789", "000000").forEach { assertTrue(Passcodes.isTooEasy(it), it) }
        listOf("2580", "1357", "0192", "112233").forEach { assertFalse(Passcodes.isTooEasy(it), it) }
    }
}
