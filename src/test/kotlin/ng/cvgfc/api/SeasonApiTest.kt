package ng.cvgfc.api

import ng.cvgfc.api.member.Role
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class SeasonApiTest : IntegrationTest() {

    @Test
    fun `only one season is active at a time`() {
        createMember("Amaka Nwosu", "0805 555 0134", setOf(Role.ADMIN))
        val token = signIn("0805 555 0134")

        val first = parse(
            mvc.perform(post("/api/seasons").bearer(token)
                .jsonBody("""{"name":"2025/26","startsOn":"2025-09-01","endsOn":"2026-08-31","activate":true}"""))
                .andExpect(status().isCreated).andReturn().response.contentAsString,
        )
        val second = parse(
            mvc.perform(post("/api/seasons").bearer(token)
                .jsonBody("""{"name":"2026/27","startsOn":"2026-09-01","endsOn":"2027-08-31"}"""))
                .andExpect(status().isCreated).andReturn().response.contentAsString,
        )

        mvc.perform(get("/api/seasons/current").bearer(token)).andExpect(jsonPath("$.name").value("2025/26"))
        mvc.perform(post("/api/seasons/${second["id"].asText()}/activate").bearer(token))
            .andExpect(jsonPath("$.active").value(true))
        mvc.perform(get("/api/seasons/current").bearer(token)).andExpect(jsonPath("$.name").value("2026/27"))
        mvc.perform(get("/api/seasons").bearer(token))
            .andExpect(jsonPath("$[1].id").value(first["id"].asText()))
            .andExpect(jsonPath("$[1].active").value(false))
    }

    @Test
    fun `end must be after start, and players cannot create seasons`() {
        createMember("Amaka Nwosu", "0805 555 0134", setOf(Role.ADMIN))
        createMember("Kola Bello", "0807 555 0155")
        val admin = signIn("0805 555 0134")
        val player = signIn("0807 555 0155")

        mvc.perform(post("/api/seasons").bearer(admin).jsonBody("""{"name":"Bad","startsOn":"2026-09-01","endsOn":"2026-08-01"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("invalid_dates"))
        mvc.perform(post("/api/seasons").bearer(player).jsonBody("""{"name":"X","startsOn":"2026-09-01","endsOn":"2027-08-01"}"""))
            .andExpect(status().isForbidden)
    }
}
