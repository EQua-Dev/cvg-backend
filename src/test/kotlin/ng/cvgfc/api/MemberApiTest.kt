package ng.cvgfc.api

import ng.cvgfc.api.member.Role
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals

class MemberApiTest : IntegrationTest() {

    private lateinit var adminToken: String
    private lateinit var playerToken: String

    @BeforeEach
    fun squad() {
        createMember("Amaka Nwosu", "0805 555 0134", setOf(Role.ADMIN, Role.TREASURER), jersey = 11)
        createMember("Kola Bello", "0807 555 0155", jersey = 7)
        adminToken = signIn("0805 555 0134")
        playerToken = signIn("0807 555 0155")
    }

    @Test
    fun `admin adds a member with defaults and it is audited`() {
        mvc.perform(
            post("/api/members").bearer(adminToken)
                .jsonBody("""{"fullName":" Sola Adeniyi ","phone":"0803 555 0000","jerseyNumber":23}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.code").value("CVG-0003"))
            .andExpect(jsonPath("$.fullName").value("Sola Adeniyi"))
            .andExpect(jsonPath("$.status").value("TRIALIST"))
            .andExpect(jsonPath("$.phone").value("0803 555 0000"))

        val actions = jdbc.queryForList("SELECT action FROM audit_event ORDER BY created_at", String::class.java)
        assertEquals("member.created", actions.last())
        val actor = jdbc.queryForObject(
            "SELECT m.full_name FROM audit_event a JOIN member m ON m.id = a.actor_id WHERE a.summary = 'Added Sola Adeniyi'",
            String::class.java,
        )
        assertEquals("Amaka Nwosu", actor)
    }

    @Test
    fun `players cannot add members`() {
        mvc.perform(post("/api/members").bearer(playerToken).jsonBody("""{"fullName":"X","phone":"0803 555 0000"}"""))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `players see the squad without other people's phone numbers`() {
        mvc.perform(get("/api/members").bearer(playerToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].fullName").value("Amaka Nwosu"))
            .andExpect(jsonPath("$[0].phone").doesNotExist())
            .andExpect(jsonPath("$[1].phone").value("0807 555 0155"))
    }

    @Test
    fun `jersey numbers are unique among current members`() {
        mvc.perform(post("/api/members").bearer(adminToken).jsonBody("""{"fullName":"Dup","phone":"0803 555 0000","jerseyNumber":7}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("jersey_taken"))
    }

    @Test
    fun `duplicate and invalid phones are rejected`() {
        mvc.perform(post("/api/members").bearer(adminToken).jsonBody("""{"fullName":"Dup","phone":"08075550155"}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("phone_taken"))
        mvc.perform(post("/api/members").bearer(adminToken).jsonBody("""{"fullName":"Bad","phone":"12345"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("invalid_phone"))
    }

    @Test
    fun `validation errors come back per field`() {
        mvc.perform(post("/api/members").bearer(adminToken).jsonBody("""{"fullName":"","phone":"0803 555 0000","jerseyNumber":120}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.fields.fullName").value("Enter a full name."))
            .andExpect(jsonPath("$.fields.jerseyNumber").value("1–99 only."))
    }

    @Test
    fun `the last admin cannot remove their own admin role or leave`() {
        val me = parse(mvc.perform(get("/api/auth/me").bearer(adminToken)).andReturn().response.contentAsString)
        val id = me["id"].asText()

        mvc.perform(put("/api/members/$id/roles").bearer(adminToken).jsonBody("""{"roles":["TREASURER"]}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("last_admin"))
        mvc.perform(put("/api/members/$id/status").bearer(adminToken).jsonBody("""{"status":"LEFT"}"""))
            .andExpect(status().isConflict)
    }

    @Test
    fun `roles can be given and searched`() {
        val kola = parse(mvc.perform(get("/api/members?q=kola").bearer(adminToken)).andReturn().response.contentAsString)[0]
        mvc.perform(put("/api/members/${kola["id"].asText()}/roles").bearer(adminToken).jsonBody("""{"roles":["CAPTAIN","COACH"]}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roles[0]").value("COACH"))
            .andExpect(jsonPath("$.roles[1]").value("CAPTAIN"))

        mvc.perform(get("/api/members?role=CAPTAIN").bearer(adminToken))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].fullName").value("Kola Bello"))
    }

    @Test
    fun `audit log is for management only`() {
        mvc.perform(get("/api/audit").bearer(playerToken)).andExpect(status().isForbidden)
        mvc.perform(get("/api/audit").bearer(adminToken))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.total").value(2))
    }
}
