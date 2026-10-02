package ng.cvgfc.api

import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.member.Role
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertTrue

class OnboardingTest : IntegrationTest() {

    private lateinit var admin: String
    private lateinit var amakaId: UUID

    @BeforeEach
    fun setUp() {
        createMember("Tunde Adeyemi", "0803 555 0192", setOf(Role.ADMIN))
        amakaId = createMember("Amaka Nwosu", "0805 555 0134", jersey = 11).id
        admin = signIn("0803 555 0192")
    }

    private fun newLink(memberId: UUID = amakaId): String {
        val body = mvc.perform(post("/api/members/$memberId/onboarding-link").bearer(admin))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val url = parse(body)["url"].asText()
        assertTrue(url.startsWith("http://localhost:3002/join/"), url)
        return url.substringAfterLast("/")
    }

    @Test
    fun `member opens the link, picks a passcode and is signed in`() {
        val token = newLink()

        mvc.perform(get("/api/onboarding/$token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.firstName").value("Amaka"))
            .andExpect(jsonPath("$.code").value("CVG-0002"))
            .andExpect(jsonPath("$.phoneHint").value("0805 ••• ••34"))
            .andExpect(jsonPath("$.alreadySetUp").value(false))

        val res = mvc.perform(post("/api/onboarding/$token/claim").jsonBody("""{"passcode":"2580"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.member.fullName").value("Amaka Nwosu"))
            .andExpect(cookie().httpOnly("cvg_session", true))
            .andReturn()
        val token2 = parse(res.response.contentAsString)["token"].asText()
        mvc.perform(get("/api/auth/me").bearer(token2)).andExpect(jsonPath("$.usesDefaultPasscode").value(false))

        // The link is used up, and the new passcode works for normal sign-in.
        mvc.perform(get("/api/onboarding/$token")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("link_invalid"))
        signIn("0805 555 0134", "2580")
    }

    @Test
    fun `a new link replaces the old one`() {
        val first = newLink()
        val second = newLink()
        mvc.perform(get("/api/onboarding/$first")).andExpect(status().isNotFound)
        mvc.perform(get("/api/onboarding/$second")).andExpect(status().isOk)
    }

    @Test
    fun `weak passcodes are refused and the link stays usable`() {
        val token = newLink()
        mvc.perform(post("/api/onboarding/$token/claim").jsonBody("""{"passcode":"1234"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("weak_passcode"))
        mvc.perform(get("/api/onboarding/$token")).andExpect(status().isOk)
    }

    @Test
    fun `members who already set a passcode are told to sign in`() {
        val token = newLink()
        val amaka = signIn("0805 555 0134")
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/auth/passcode").bearer(amaka)
            .jsonBody("""{"currentPasscode":"0134","newPasscode":"2580"}"""))
        mvc.perform(get("/api/onboarding/$token")).andExpect(jsonPath("$.alreadySetUp").value(true))
        mvc.perform(post("/api/onboarding/$token/claim").jsonBody("""{"passcode":"1357"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("already_set_up"))
    }

    @Test
    fun `bad tokens, departed members and players are refused`() {
        mvc.perform(get("/api/onboarding/nonsense")).andExpect(status().isNotFound)

        val token = newLink()
        jdbc.update("UPDATE member SET status = ? WHERE id = ?", MemberStatus.LEFT.name, amakaId)
        mvc.perform(get("/api/onboarding/$token")).andExpect(status().isNotFound)

        val kola = createMember("Kola Bello", "0807 555 0155")
        mvc.perform(post("/api/members/${kola.id}/onboarding-link").bearer(signIn("0807 555 0155")))
            .andExpect(status().isForbidden)
    }
}
