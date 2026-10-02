package ng.cvgfc.api

import ng.cvgfc.api.member.Role
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals

/** Tiny but valid file headers are enough: we only sniff the first bytes. */
val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200) { 7 }
val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(100)

class ProfileTest : IntegrationTest() {

    private lateinit var amaka: String
    private lateinit var amakaId: UUID

    @BeforeEach
    fun setUp() {
        amakaId = createMember("Amaka Nwosu", "0805 555 0134").id
        amaka = signIn("0805 555 0134")
    }

    private fun uploadPhoto(token: String, bytes: ByteArray) =
        mvc.perform(multipart("/api/me/photo").file(MockMultipartFile("file", "me.jpg", "image/jpeg", bytes))
            .with { it.method = "PUT"; it }.bearer(token))

    private val full = """
        {"favouredPosition":"CM","otherPositions":["CDM","CAM"],"dominantFoot":"LEFT","weakFoot":3,
         "strengths":["Passing","Vision"],"weaknesses":["Heading"],"heightCm":178,"dateOfBirth":"1998-05-14",
         "stateOfOrigin":"Anambra","preferredJersey":8,"emergencyName":"Ify Nwosu","emergencyPhone":"0805 555 0999",
         "consentPublic":true}
    """.trimIndent()

    @Test
    fun `a new profile lists what is missing`() {
        mvc.perform(get("/api/me/profile").bearer(amaka))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.complete").value(false))
            .andExpect(jsonPath("$.missing[0]").value("photo"))
            .andExpect(jsonPath("$.missing.length()").value(5))
    }

    @Test
    fun `filling everything plus a photo completes the profile`() {
        mvc.perform(put("/api/me/profile").bearer(amaka).jsonBody(full))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.positionGroup").value("MID"))
            .andExpect(jsonPath("$.emergencyContact.phone").value("0805 555 0999"))
            .andExpect(jsonPath("$.missing[0]").value("photo"))
            .andExpect(jsonPath("$.complete").value(false))

        uploadPhoto(amaka, JPEG)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.photoUrl").value(org.hamcrest.Matchers.startsWith("/api/members/$amakaId/photo?v=")))

        val completedAt = jdbc.queryForObject("SELECT completed_at FROM member_profile", Any::class.java)
        kotlin.test.assertNotNull(completedAt)

        mvc.perform(get("/api/members/$amakaId/photo").bearer(amaka))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/jpeg"))
            .andExpect(content().bytes(JPEG))
    }

    @Test
    fun `squad-mates see football details but not private ones`() {
        mvc.perform(put("/api/me/profile").bearer(amaka).jsonBody(full))
        createMember("Kola Bello", "0807 555 0155")
        val kola = signIn("0807 555 0155")

        mvc.perform(get("/api/members/$amakaId/profile").bearer(kola))
            .andExpect(jsonPath("$.favouredPosition").value("CM"))
            .andExpect(jsonPath("$.strengths[0]").value("Passing"))
            .andExpect(jsonPath("$.age").isNumber)
            .andExpect(jsonPath("$.dateOfBirth").doesNotExist())
            .andExpect(jsonPath("$.emergencyContact").doesNotExist())

        createMember("Coach Chinedu", "0806 555 0110", setOf(Role.COACH))
        mvc.perform(get("/api/members/$amakaId/profile").bearer(signIn("0806 555 0110")))
            .andExpect(jsonPath("$.emergencyContact.name").value("Ify Nwosu"))
    }

    @Test
    fun `invalid picks come back per field`() {
        mvc.perform(put("/api/me/profile").bearer(amaka).jsonBody(
            """{"favouredPosition":"XX","otherPositions":["CM","CB","ST","GK"],"strengths":["Pace","Pace","Speed"],
               "weaknesses":[],"weakFoot":9,"heightCm":300,"emergencyPhone":"123"}""",
        ))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fields.favouredPosition").value("Pick a position."))
            .andExpect(jsonPath("$.fields.otherPositions").value("Up to 3."))
            .andExpect(jsonPath("$.fields.strengths").value("Pick from the list."))
            .andExpect(jsonPath("$.fields.weakFoot").value("1 to 5 stars."))
            .andExpect(jsonPath("$.fields.heightCm").exists())
            .andExpect(jsonPath("$.fields.emergencyPhone").value("Enter a valid phone number."))

        mvc.perform(put("/api/me/profile").bearer(amaka).jsonBody(
            """{"favouredPosition":"CM","otherPositions":["CM"],"strengths":["Pace"],"weaknesses":["Pace"]}""",
        ))
            .andExpect(jsonPath("$.fields.otherPositions").value("That's already your main position."))
            .andExpect(jsonPath("$.fields.weaknesses").value("Can't be a strength and a weakness."))
    }

    @Test
    fun `photos must really be images, and a new photo replaces the old`() {
        uploadPhoto(amaka, "not an image".toByteArray())
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("photo_type"))

        uploadPhoto(amaka, JPEG).andExpect(status().isOk)
        uploadPhoto(amaka, PNG).andExpect(status().isOk)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM media_asset", Int::class.java))
        mvc.perform(get("/api/members/$amakaId/photo").bearer(amaka))
            .andExpect(header().string("Content-Type", "image/png"))
    }

    @Test
    fun `options list the pick lists`() {
        mvc.perform(get("/api/profile/options").bearer(amaka))
            .andExpect(jsonPath("$.positions.length()").value(15))
            .andExpect(jsonPath("$.positions[0].code").value("GK"))
            .andExpect(jsonPath("$.traits.length()").value(23))
            .andExpect(jsonPath("$.states.length()").value(37))
    }
}
