package ng.cvgfc.api

import ng.cvgfc.api.member.Role
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertTrue

class IdCardTest : IntegrationTest() {

    private lateinit var amaka: String
    private lateinit var amakaId: UUID

    @BeforeEach
    fun setUp() {
        createMember("Tunde Adeyemi", "0803 555 0192", setOf(Role.ADMIN))
        amakaId = createMember("Amaka Nwosu", "0805 555 0134", jersey = 11).id
        amaka = signIn("0805 555 0134")
        val admin = signIn("0803 555 0192")
        mvc.perform(post("/api/seasons").bearer(admin)
            .jsonBody("""{"name":"2026/27","startsOn":"2026-09-01","endsOn":"2027-08-31","activate":true}"""))
    }

    private fun verifyPath(): String {
        val card = parse(mvc.perform(get("/api/me/card").bearer(amaka)).andReturn().response.contentAsString)
        val url = card["verifyUrl"].asText()
        assertTrue(url.matches(Regex("""http://localhost:3000/verify/CVG-0002/[0-9a-f]{12}""")), url)
        return url.substringAfter("/verify/")
    }

    private fun profile(consent: Boolean) {
        mvc.perform(put("/api/me/profile").bearer(amaka).jsonBody(
            """{"favouredPosition":"CM","dominantFoot":"LEFT","emergencyName":"Ify Nwosu","emergencyPhone":"0805 555 0999","consentPublic":$consent}""",
        ))
        mvc.perform(multipart("/api/me/photo").file(MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG))
            .with { it.method = "PUT"; it }.bearer(amaka))
    }

    @Test
    fun `the card has everything printed on it`() {
        profile(consent = true)
        mvc.perform(get("/api/me/card").bearer(amaka))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("CVG-0002"))
            .andExpect(jsonPath("$.jerseyNumber").value(11))
            .andExpect(jsonPath("$.position").value("Central mid"))
            .andExpect(jsonPath("$.season").value("2026/27"))
            .andExpect(jsonPath("$.validUntil").value("2027-08-31"))
            .andExpect(jsonPath("$.photoUrl").exists())
            .andExpect(jsonPath("$.emergencyContact.name").value("Ify Nwosu"))
    }

    @Test
    fun `anyone can verify a scanned card, with no private details`() {
        profile(consent = true)
        val path = verifyPath()
        mvc.perform(get("/api/public/verify/$path"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.fullName").value("Amaka Nwosu"))
            .andExpect(jsonPath("$.current").value(true))
            .andExpect(jsonPath("$.season").value("2026/27"))
            .andExpect(jsonPath("$.photoUrl").value("/api/public/verify/$path/photo"))
            .andExpect(jsonPath("$.emergencyContact").doesNotExist())
            .andExpect(jsonPath("$.phone").doesNotExist())
        mvc.perform(get("/api/public/verify/$path/photo")).andExpect(status().isOk)
    }

    @Test
    fun `without consent the photo and position stay hidden`() {
        profile(consent = false)
        val path = verifyPath()
        mvc.perform(get("/api/public/verify/$path"))
            .andExpect(jsonPath("$.fullName").value("Amaka Nwosu"))
            .andExpect(jsonPath("$.photoUrl").doesNotExist())
            .andExpect(jsonPath("$.position").doesNotExist())
        mvc.perform(get("/api/public/verify/$path/photo")).andExpect(status().isNotFound)
    }

    @Test
    fun `member codes cannot be guessed without the QR signature`() {
        val path = verifyPath()
        mvc.perform(get("/api/public/verify/CVG-0002/000000000000")).andExpect(status().isNotFound)
        mvc.perform(get("/api/public/verify/CVG-0001/${path.substringAfter("/")}"))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("card_not_found"))
    }

    @Test
    fun `members who left show as not current`() {
        val path = verifyPath()
        jdbc.update("UPDATE member SET status = 'LEFT' WHERE id = ?", amakaId)
        mvc.perform(get("/api/public/verify/$path"))
            .andExpect(jsonPath("$.status").value("LEFT"))
            .andExpect(jsonPath("$.current").value(false))
    }
}
