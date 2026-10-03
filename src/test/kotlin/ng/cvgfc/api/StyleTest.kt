package ng.cvgfc.api

import ng.cvgfc.api.match.Formations
import ng.cvgfc.api.member.Role
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyleTest : IntegrationTest() {

    private lateinit var ids: Map<String, UUID>
    private lateinit var tokens: Map<String, String>

    @BeforeEach
    fun squad() {
        val people = listOf(
            Triple("Chinedu Okafor", "0806 555 0110", "CB"),
            Triple("Tunde Adeyemi", "0803 555 0192", "ST"),
            Triple("Kola Bello", "0807 555 0155", "GK"),
            Triple("Femi Adebayo", "0808 555 0177", "CB"),
            Triple("Amaka Nwosu", "0805 555 0134", "CM"),
        )
        ids = people.mapIndexed { i, (name, phone, pos) ->
            val m = createMember(name, phone, if (i == 0) setOf(Role.COACH) else emptySet(), jersey = (i + 2).toShort())
            jdbc.update("INSERT INTO member_profile (member_id, favoured_position) VALUES (?, ?)", m.id, pos)
            name.split(" ")[0] to m.id
        }.toMap()
        tokens = people.associate { (name, phone, _) -> name.split(" ")[0] to signIn(phone) }
    }

    private fun t(n: String) = tokens.getValue(n)

    private fun questionnaire(name: String, group: String, fits: String, role: String, roleName: String) {
        jdbc.update(
            "INSERT INTO profiling_response (id, member_id, questionnaire_version, position_group, answers, result, submitted_at) VALUES (gen_random_uuid(), ?, 1, ?, '{}', ?::jsonb, now() - interval '1 day')",
            ids[name], group,
            """{"version":1,"group":"$group","planFits":$fits,"topPlan":"CTR","mainRole":{"code":"$role","name":"$roleName"},"label":"x","lowConfidence":false}""",
        )
    }

    private fun setRole(name: String, role: String?) =
        mvc.perform(put("/api/styles/${ids[name]}/role").bearer(t("Chinedu")).jsonBody(mapOf("role" to role)))

    @Test
    fun `neighbours are side by side or just in front or behind`() {
        val f = Formations.find(11, "4-4-2")!!
        val n = Formations.neighbours(f).toSet()
        assertTrue((0 to 2) in n && (0 to 3) in n, "keeper with both centre-backs")
        assertFalse((0 to 1) in n || (0 to 4) in n, "not the full-backs")
        assertTrue((1 to 2) in n && (2 to 3) in n, "back four side by side")
        assertFalse(n.any { it.first == 0 && it.second >= 9 }, "keeper is not next to the strikers")
        assertTrue(Formations.all.all { Formations.neighbours(it).isNotEmpty() })
    }

    @Test
    fun `plan fit blends questionnaire 35 and ratings 50, label is plan plus role`() {
        questionnaire("Tunde", "ATT", """{"POS":40,"CTR":90,"PRS":60,"BLK":20,"DIR":50}""", "INF", "Inside Forward")
        val window = UUID.randomUUID()
        jdbc.update("INSERT INTO rating_window (id, club_id, title, opens_at, closes_at, closed_at) VALUES (?, '00000000-0000-0000-0000-00000000c0c0', 'R1', now() - interval '8 days', now() - interval '1 day', now() - interval '1 day')", window)
        // Card stats: counter attributes (PAC MOV FIN DRI) at 99 → ratings fit 100 for CTR.
        jdbc.update("INSERT INTO player_card (id, window_id, member_id, stats, peer_counts, self_stats, position) VALUES (gen_random_uuid(), ?, ?, ?::jsonb, '{}', '{}', 'ST')",
            window, ids["Tunde"], """{"PAC":99,"MOV":99,"FIN":99,"DRI":99,"PAS":30,"VIS":30,"CTL":30,"CMP":30,"STA":64,"TAC":64,"MRK":30,"STR":30,"AER":30,"SHO":30}""")

        val me = parse(mvc.perform(get("/api/styles/me").bearer(t("Tunde"))).andReturn().response.contentAsString)
        val ctr = me["style"]["planFits"].first { it["code"].asText() == "CTR" }
        assertEquals(90, ctr["self"].asInt())
        assertEquals(100, ctr["ratings"].asInt())
        assertEquals(96, ctr["fit"].asInt(), "(90×35 + 100×50) ÷ 85")
        assertEquals("Counter-attacking Inside Forward", me["style"]["label"].asText())
        assertFalse(me["questionnaireDue"].asBoolean())

        // Teammates see the label and bars, not the breakdown.
        val seen = parse(mvc.perform(get("/api/styles/${ids["Tunde"]}").bearer(t("Kola"))).andReturn().response.contentAsString)
        assertEquals("Counter-attacking Inside Forward", seen["label"].asText())
        assertTrue(seen["planFits"][1].get("self") == null)
        assertTrue(seen.get("selfRole") == null)

        // A new questionnaire round makes it due again.
        mvc.perform(post("/api/styles/rounds").bearer(t("Tunde"))).andExpect(status().isForbidden)
        mvc.perform(post("/api/styles/rounds").bearer(t("Chinedu"))).andExpect(status().isOk)
        mvc.perform(get("/api/styles/me").bearer(t("Tunde"))).andExpect(jsonPath("$.questionnaireDue").value(true))
    }

    @Test
    fun `squad votes a role, coach sees the disagreement and makes the call`() {
        questionnaire("Tunde", "ATT", """{"POS":40,"CTR":90,"PRS":60,"BLK":20,"DIR":50}""", "INF", "Inside Forward")
        mvc.perform(put("/api/ratings/me/${ids["Tunde"]}/role").bearer(t("Kola")).jsonBody("""{"role":"TGT"}""")).andExpect(status().isConflict)
        mvc.perform(post("/api/ratings/windows").bearer(t("Chinedu")).jsonBody("{}")).andExpect(status().isCreated)

        val sheet = parse(mvc.perform(get("/api/ratings/me/${ids["Tunde"]}/role").bearer(t("Kola"))).andReturn().response.contentAsString)
        assertEquals(7, sheet["roles"].size(), "seven attacking roles")
        mvc.perform(put("/api/ratings/me/${ids["Tunde"]}/role").bearer(t("Tunde")).jsonBody("""{"role":"TGT"}""")).andExpect(status().isBadRequest)
        mvc.perform(put("/api/ratings/me/${ids["Tunde"]}/role").bearer(t("Kola")).jsonBody("""{"role":"BWM"}""")).andExpect(status().isBadRequest)
        for (voter in listOf("Kola", "Femi", "Amaka")) {
            mvc.perform(put("/api/ratings/me/${ids["Tunde"]}/role").bearer(t(voter)).jsonBody("""{"role":"TGT"}""")).andExpect(status().isOk)
        }
        mvc.perform(put("/api/ratings/me/${ids["Tunde"]}/role").bearer(t("Chinedu")).jsonBody("""{"role":"POA"}""")).andExpect(jsonPath("$.mine").value("POA"))

        val squad = parse(mvc.perform(get("/api/styles").bearer(t("Chinedu"))).andReturn().response.contentAsString)
        val tunde = squad.first { it["memberId"].asText() == ids["Tunde"].toString() }
        assertTrue(tunde["disagree"].asBoolean())
        assertEquals("TGT", tunde["peerRoles"][0]["code"].asText())
        assertEquals(3, tunde["peerRoles"][0]["votes"].asInt())
        assertEquals("INF", tunde["role"]["code"].asText(), "until the coach decides, the player's own answer stands")

        setRole("Tunde", "BWM").andExpect(status().isBadRequest)
        setRole("Tunde", "TGT").andExpect(status().isOk).andExpect(jsonPath("$.role.code").value("TGT"))
            .andExpect(jsonPath("$.label").value("Counter-attacking Target Man"))
        mvc.perform(put("/api/styles/${ids["Tunde"]}/role").bearer(t("Kola")).jsonBody("""{"role":"POA"}""")).andExpect(status().isForbidden)
        setRole("Tunde", null).andExpect(jsonPath("$.role.code").value("INF"))
    }

    @Test
    fun `chemistry links neighbours by role, plan rules and team bonus`() {
        setRole("Chinedu", "STP")
        setRole("Femi", "CVR")
        setRole("Amaka", "AP")
        setRole("Tunde", "POA")
        setRole("Kola", "SHS")
        val slots = """[{"idx":0,"memberId":"${ids["Kola"]}"},{"idx":1,"memberId":"${ids["Chinedu"]}"},{"idx":2,"memberId":"${ids["Femi"]}"},{"idx":3,"memberId":"${ids["Amaka"]}"},{"idx":4,"memberId":"${ids["Tunde"]}"}]"""

        val calm = parse(mvc.perform(post("/api/chemistry").bearer(t("Chinedu")).jsonBody("""{"teamSize":5,"formation":"2-1-1","slots":$slots}"""))
            .andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(2, calm["green"].asInt(), "Stopper + Covering, Poacher + Playmaker")
        assertEquals(0, calm["red"].asInt())
        assertEquals(2, calm["total"].asInt())
        assertTrue(calm["links"].any { it["a"].asInt() == 1 && it["b"].asInt() == 2 && it["link"].asText() == "GREEN" })

        val press = parse(mvc.perform(post("/api/chemistry").bearer(t("Chinedu")).jsonBody("""{"teamSize":5,"formation":"2-1-1","plan":"PRS","slots":$slots}"""))
            .andReturn().response.contentAsString)
        assertEquals(2, press["red"].asInt(), "shot-stopper behind a high line, next to both centre-backs")
        assertEquals(0, press["total"].asInt())

        // Two stoppers together: amber.
        setRole("Femi", "STP")
        val amber = parse(mvc.perform(post("/api/chemistry").bearer(t("Chinedu")).jsonBody("""{"teamSize":5,"formation":"2-1-1","slots":$slots}"""))
            .andReturn().response.contentAsString)
        assertEquals(1, amber["amber"].asInt())
        mvc.perform(post("/api/chemistry").bearer(t("Tunde")).jsonBody("""{"teamSize":5,"formation":"2-1-1","slots":[]}""")).andExpect(status().isForbidden)
    }

    @Test
    fun `coach edits the chemistry rules`() {
        val start = parse(mvc.perform(get("/api/chemistry/rules").bearer(t("Chinedu"))).andReturn().response.contentAsString).size()
        assertEquals(16, start)
        mvc.perform(post("/api/chemistry/rules").bearer(t("Chinedu")).jsonBody("""{"roleA":"XYZ","roleB":"POA","link":"GREEN"}""")).andExpect(status().isBadRequest)
        val created = parse(mvc.perform(post("/api/chemistry/rules").bearer(t("Chinedu")).jsonBody("""{"roleA":"WNG","roleB":"AFB","link":"GREEN","note":"Overlap"}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString)
        assertEquals("Winger", created["nameA"].asText())
        mvc.perform(delete("/api/chemistry/rules/${created["id"].asText()}").bearer(t("Tunde"))).andExpect(status().isForbidden)
        mvc.perform(delete("/api/chemistry/rules/${created["id"].asText()}").bearer(t("Chinedu"))).andExpect(status().isNoContent)
    }
}
