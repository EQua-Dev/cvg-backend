package ng.cvgfc.api

import ng.cvgfc.api.member.MemberStatus
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthFlowTest : IntegrationTest() {

    private val phone = "0803 555 0192"
    private val e164 = "+2348035550192"

    @Test
    fun `registered member signs in with the SMS code`() {
        createMember("Tunde Adeyemi", phone)

        mvc.perform(post("/api/auth/code").jsonBody("""{"phone":"$phone"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.expiresInSeconds").value(600))

        val message = sms.sent.single().message
        assertTrue(message.matches(Regex("""\d{6} is your CVG FC code.*""")), message)

        val result = mvc.perform(
            post("/api/auth/verify").jsonBody("""{"phone":"$phone","code":"${sms.lastCodeFor(e164)}"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.member.fullName").value("Tunde Adeyemi"))
            .andExpect(jsonPath("$.member.code").value("CVG-0001"))
            .andExpect(cookie().httpOnly("cvg_session", true))
            .andExpect(cookie().sameSite("cvg_session", "Lax"))
            .andReturn()

        val sessionCookie = result.response.getCookie("cvg_session")!!
        mvc.perform(get("/api/auth/me").cookie(sessionCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.phone").value("0803 555 0192"))
    }

    @Test
    fun `unknown and departed numbers are told they are not on the register`() {
        createMember("Gone Player", "0806 555 0110", status = MemberStatus.LEFT)

        mvc.perform(post("/api/auth/code").jsonBody("""{"phone":"0809 555 0000"}"""))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("not_registered"))
        mvc.perform(post("/api/auth/code").jsonBody("""{"phone":"0806 555 0110"}"""))
            .andExpect(status().isNotFound)
        assertTrue(sms.sent.isEmpty())
    }

    @Test
    fun `wrong codes are counted and lock the challenge after five tries`() {
        createMember("Tunde Adeyemi", phone)
        mvc.perform(post("/api/auth/code").jsonBody("""{"phone":"$phone"}"""))
        val good = sms.lastCodeFor(e164)
        val bad = if (good == "000000") "111111" else "000000"

        repeat(5) {
            mvc.perform(post("/api/auth/verify").jsonBody("""{"phone":"$phone","code":"$bad"}"""))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("wrong_code"))
        }
        // Even the right code is refused once attempts are used up.
        mvc.perform(post("/api/auth/verify").jsonBody("""{"phone":"$phone","code":"$good"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("too_many_attempts"))
    }

    @Test
    fun `codes are single use`() {
        createMember("Tunde Adeyemi", phone)
        mvc.perform(post("/api/auth/code").jsonBody("""{"phone":"$phone"}"""))
        val code = sms.lastCodeFor(e164)
        mvc.perform(post("/api/auth/verify").jsonBody("""{"phone":"$phone","code":"$code"}""")).andExpect(status().isOk)
        mvc.perform(post("/api/auth/verify").jsonBody("""{"phone":"$phone","code":"$code"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("code_expired"))
    }

    @Test
    fun `code requests are rate limited per phone`() {
        createMember("Tunde Adeyemi", phone)
        repeat(5) { mvc.perform(post("/api/auth/code").jsonBody("""{"phone":"$phone"}""")).andExpect(status().isOk) }
        mvc.perform(post("/api/auth/code").jsonBody("""{"phone":"$phone"}"""))
            .andExpect(status().isTooManyRequests)
    }

    @Test
    fun `cookie-authenticated writes need the client header`() {
        createMember("Tunde Adeyemi", phone)
        mvc.perform(post("/api/auth/code").jsonBody("""{"phone":"$phone"}"""))
        val sessionCookie = mvc.perform(
            post("/api/auth/verify").jsonBody("""{"phone":"$phone","code":"${sms.lastCodeFor(e164)}"}"""),
        ).andReturn().response.getCookie("cvg_session")!!

        mvc.perform(post("/api/auth/sign-out").cookie(sessionCookie)).andExpect(status().isForbidden)
        mvc.perform(post("/api/auth/sign-out").cookie(sessionCookie).client()).andExpect(status().isNoContent)
        // Signed out: the session no longer works.
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
}
