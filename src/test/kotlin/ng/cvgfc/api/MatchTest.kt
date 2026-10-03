package ng.cvgfc.api

import ng.cvgfc.api.match.Factor
import ng.cvgfc.api.match.Formations
import ng.cvgfc.api.match.SelectionService
import ng.cvgfc.api.member.Role
import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.money.CLUB_ZONE
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatchTest : IntegrationTest() {

    private lateinit var coach: String
    private lateinit var tunde: String
    private lateinit var kola: String
    private lateinit var ids: Map<String, UUID>

    @BeforeEach
    fun squad() {
        val people = listOf(
            Triple("Chinedu Okafor", "0806 555 0110", "CB"),
            Triple("Amaka Nwosu", "0805 555 0134", "CM"),
            Triple("Tunde Adeyemi", "0803 555 0192", "ST"),
            Triple("Kola Bello", "0807 555 0155", "GK"),
            Triple("Femi Adebayo", "0808 555 0177", "CB"),
            Triple("Sani Musa", "0809 555 0188", "LB"),
        )
        ids = people.mapIndexed { i, (name, phone, pos) ->
            val roles = when (i) { 0 -> setOf(Role.COACH); 1 -> setOf(Role.CAPTAIN); else -> emptySet() }
            val m = createMember(name, phone, roles, jersey = (i + 2).toShort())
            jdbc.update("INSERT INTO member_profile (member_id, favoured_position, other_positions) VALUES (?, ?, ?::jsonb)",
                m.id, pos, if (pos == "LB") """["CB"]""" else "[]")
            name.split(" ")[0] to m.id
        }.toMap()
        coach = signIn("0806 555 0110")
        tunde = signIn("0803 555 0192")
        kola = signIn("0807 555 0155")
    }

    private fun create(daysAhead: Long, extra: String = ""): UUID {
        val date = LocalDate.now(CLUB_ZONE).plusDays(daysAhead)
        val body = """{"opponent":"Gwarinpa FC","date":"$date","kickoff":"16:00","venue":"Area 1 Field","side":"HOME","type":"FRIENDLY","teamSize":5$extra}"""
        val res = mvc.perform(post("/api/matches").bearer(coach).jsonBody(body)).andExpect(status().isCreated).andReturn()
        return UUID.fromString(parse(res.response.contentAsString)["id"].asText())
    }

    /** Moves kickoff to a few hours ago so the result can be entered. */
    private fun kickedOff(id: UUID) = jdbc.update("UPDATE match SET kickoff_at = now() - interval '3 hours' WHERE id = ?", id)

    private fun detail(id: UUID, token: String) = parse(mvc.perform(get("/api/matches/$id").bearer(token)).andReturn().response.contentAsString)

    private fun lineupBody(formation: String, slots: Map<Int, UUID>, captain: UUID? = null): String {
        val list = slots.entries.joinToString(",") { """{"idx":${it.key},"memberId":"${it.value}"}""" }
        return """{"formation":"$formation","slots":[$list]${captain?.let { ""","captainId":"$it"""" } ?: ""}}"""
    }

    private fun appearance(name: String, started: Boolean = true, pos: String? = null) =
        """{"memberId":"${ids.getValue(name)}","started":$started${pos?.let { ""","position":"$it"""" } ?: ""}}"""

    private fun com.fasterxml.jackson.databind.JsonNode.absent(field: String) = get(field)?.isNull ?: true

    @Test
    fun `coach creates a fixture, everyone is in by default, players can say out`() {
        mvc.perform(post("/api/matches").bearer(tunde).jsonBody("""{"opponent":"x","date":"${LocalDate.now().plusDays(2)}","kickoff":"16:00","venue":"y"}"""))
            .andExpect(status().isForbidden)
        val id = create(3, ""","gamePlan":"CTR","planB":"CTR"""")

        val list = parse(mvc.perform(get("/api/matches").bearer(tunde)).andReturn().response.contentAsString)
        assertEquals(1, list.size())
        assertEquals(6, list[0]["inCount"].asInt())
        assertEquals("IN", list[0]["me"]["status"].asText())
        assertTrue(list[0].absent("planB"), "plan B can't equal plan A")

        mvc.perform(put("/api/matches/$id/availability").bearer(kola).jsonBody("""{"status":"OUT"}""")).andExpect(status().isBadRequest)
        mvc.perform(put("/api/matches/$id/availability").bearer(kola).jsonBody("""{"status":"OUT","reason":"WORK"}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.outCount").value(1))
        mvc.perform(get("/api/matches/$id/squad").bearer(tunde)).andExpect(status().isForbidden)
        val squad = parse(mvc.perform(get("/api/matches/$id/squad").bearer(coach)).andReturn().response.contentAsString)
        assertEquals("GK", squad[0]["position"].asText())
        assertEquals("OUT", squad[0]["availability"].asText())

        // Coach can change anyone, and it's logged.
        mvc.perform(put("/api/matches/$id/availability/${ids["Kola"]}").bearer(coach).jsonBody("""{"status":"IN"}""")).andExpect(status().isOk)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'match.availability_set'", Int::class.java))
    }

    @Test
    fun `selection scores every player for every spot and auto-fills a sensible five`() {
        val id = create(3)
        // Femi owes overdue dues: that costs him the dues factor.
        jdbc.update("INSERT INTO collection (id, club_id, title, type, amount_kobo, due_date, audience) VALUES (gen_random_uuid(), '00000000-0000-0000-0000-00000000c0c0', 'Old dues', 'DUES', 200000, current_date - 10, 'ACTIVE')")
        jdbc.update("INSERT INTO collection_member (collection_id, member_id) SELECT id, ? FROM collection", ids["Femi"])

        val sel = parse(mvc.perform(get("/api/matches/$id/selection?formation=2-1-1").bearer(coach)).andReturn().response.contentAsString)
        assertEquals("2-1-1", sel["formation"]["name"].asText())
        assertEquals(listOf("OVR", "FORM", "ATTENDANCE", "PLAN"), sel["unavailableFactors"].map { it.asText() }, "no ratings, matches or training yet; no game plan set")
        val weights = sel["weights"]
        assertTrue((98..102).contains(weights.fields().asSequence().sumOf { it.value.asInt() }))

        val suggestion = sel["suggestion"]
        assertEquals(ids["Kola"].toString(), suggestion["0"].asText(), "the keeper goes in goal")
        assertEquals(ids["Tunde"].toString(), suggestion["4"].asText(), "the striker up front")
        assertEquals(ids["Amaka"].toString(), suggestion["3"].asText())
        assertEquals(5, suggestion.size())
        val femi = sel["candidates"].first { it["memberId"].asText() == ids["Femi"].toString() }
        assertEquals(0, femi["factors"]["DUES"].asInt())
        val sani = sel["candidates"].first { it["memberId"].asText() == ids["Sani"].toString() }
        assertTrue(sani["slotScores"]["1"].asInt() > sani["slotScores"]["4"].asInt(), "a left-back who also plays CB scores better at CB than up front")
        assertEquals(Formations.benchSize(5), sel["benchSize"].asInt())

        // Someone who is out isn't suggested.
        mvc.perform(put("/api/matches/$id/availability").bearer(kola).jsonBody("""{"status":"OUT","reason":"INJURED"}"""))
        val again = parse(mvc.perform(get("/api/matches/$id/selection").bearer(coach)).andReturn().response.contentAsString)
        assertFalse(again["suggestion"].elements().asSequence().any { it.asText() == ids["Kola"].toString() })

        // Coach can change the weights for this match.
        mvc.perform(put("/api/matches/$id/weights").bearer(coach).jsonBody("""{"weights":{"POSITION":100}}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.POSITION").value(100)).andExpect(jsonPath("$.DUES").value(0))
        mvc.perform(put("/api/matches/$id/weights").bearer(tunde).jsonBody("""{"weights":{"POSITION":100}}""")).andExpect(status().isForbidden)
    }

    @Test
    fun `lineup is private until published and needs every spot filled`() {
        val id = create(3)
        val five = mapOf(0 to ids.getValue("Kola"), 1 to ids.getValue("Chinedu"), 2 to ids.getValue("Femi"), 3 to ids.getValue("Amaka"))
        mvc.perform(put("/api/matches/$id/lineup").bearer(coach).jsonBody(lineupBody("2-1-1", five + (4 to ids.getValue("Kola")))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("duplicate_player"))
        mvc.perform(put("/api/matches/$id/lineup").bearer(coach).jsonBody(lineupBody("4-4-2", five))).andExpect(status().isBadRequest)
        mvc.perform(put("/api/matches/$id/lineup").bearer(coach).jsonBody(lineupBody("2-1-1", five, captain = ids["Tunde"])))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("not_in_lineup"))
        mvc.perform(put("/api/matches/$id/lineup").bearer(coach).jsonBody(lineupBody("2-1-1", five, captain = ids["Amaka"]))).andExpect(status().isOk)
        mvc.perform(post("/api/matches/$id/lineup/publish").bearer(coach)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.message").value("1 spot still empty."))

        assertTrue(detail(id, tunde).absent("lineup"), "players don't see drafts")
        val withGuest = """{"formation":"2-1-1","slots":[${five.entries.joinToString(",") { """{"idx":${it.key},"memberId":"${it.value}"}""" }},{"idx":4,"guestName":"Musa (guest)"},{"idx":100,"memberId":"${ids["Tunde"]}"}],"captainId":"${ids["Amaka"]}"}"""
        mvc.perform(put("/api/matches/$id/lineup").bearer(coach).jsonBody(withGuest)).andExpect(status().isOk)
        mvc.perform(post("/api/matches/$id/lineup/publish").bearer(coach)).andExpect(status().isOk)

        val seen = detail(id, tunde)
        assertEquals("Musa (guest)", seen["lineup"]["slots"][4]["name"].asText())
        assertEquals("ST", seen["lineup"]["slots"][4]["position"].asText())
        assertEquals("BENCH", seen["match"]["myLineup"].asText())
        assertEquals("STARTING", detail(id, kola)["match"]["myLineup"].asText())
    }

    @Test
    fun `result, POTM vote, opinions, fee collection and records`() {
        val id = create(0, ""","feeKobo":100000""")
        kickedOff(id)
        val apps = listOf(appearance("Kola", pos = "GK"), appearance("Chinedu", pos = "CB"), appearance("Femi", pos = "CB"),
            appearance("Amaka", pos = "CM"), appearance("Tunde", pos = "ST"), appearance("Sani", started = false)).joinToString(",")
        val goal = { s: String, a: String? -> """{"scorerId":"${ids[s]}"${a?.let { ""","assistId":"${ids[it]}"""" } ?: ""}}""" }

        mvc.perform(put("/api/matches/$id/result").bearer(coach).jsonBody("""{"ourScore":2,"theirScore":0,"appearances":[$apps],"goals":[${goal("Tunde", "Amaka")}]}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("goals_mismatch"))
        mvc.perform(put("/api/matches/$id/result").bearer(coach).jsonBody("""{"ourScore":1,"theirScore":0,"appearances":[$apps],"goals":[${goal("Tunde", "Tunde")}]}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("invalid_assist"))
        mvc.perform(put("/api/matches/$id/result").bearer(tunde).jsonBody("""{"ourScore":0,"theirScore":0,"appearances":[$apps]}""")).andExpect(status().isForbidden)

        val saved = mvc.perform(put("/api/matches/$id/result").bearer(coach).jsonBody(
            """{"ourScore":3,"theirScore":0,"appearances":[$apps],"goals":[${goal("Tunde", "Amaka")},${goal("Tunde", null)},{"ownGoal":true}]}""",
        )).andExpect(status().isOk).andReturn()
        val result = parse(saved.response.contentAsString)
        assertEquals("PLAYED", result["match"]["status"].asText())
        assertEquals("W", result["match"]["outcome"].asText())
        assertTrue(result["potm"]["open"].asBoolean())
        assertEquals(setOf(ids["Kola"].toString(), ids["Chinedu"].toString(), ids["Femi"].toString()),
            result["result"]["cleanSheets"].map { it.asText() }.toSet())

        // A match fee collection for the six who played.
        val feeId = result["result"]["feeCollectionId"].asText()
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM collection_member WHERE collection_id = ?::uuid", Int::class.java, feeId))

        // POTM: squad only, never yourself, can change your mind, totals hidden while open.
        val outsider = createMember("Ola Yusuf", "0810 555 0199", jersey = 30, status = MemberStatus.ACTIVE)
        val ola = signIn("0810 555 0199")
        mvc.perform(post("/api/matches/$id/potm/vote").bearer(ola).jsonBody("""{"nomineeId":"${ids["Tunde"]}"}""")).andExpect(status().isForbidden)
        mvc.perform(post("/api/matches/$id/potm/vote").bearer(tunde).jsonBody("""{"nomineeId":"${ids["Tunde"]}"}""")).andExpect(status().isBadRequest)
        mvc.perform(post("/api/matches/$id/potm/vote").bearer(tunde).jsonBody("""{"nomineeId":"${outsider.id}"}""")).andExpect(status().isBadRequest)
        mvc.perform(post("/api/matches/$id/potm/vote").bearer(kola).jsonBody("""{"nomineeId":"${ids["Amaka"]}"}""")).andExpect(status().isOk)
        mvc.perform(post("/api/matches/$id/potm/vote").bearer(kola).jsonBody("""{"nomineeId":"${ids["Tunde"]}"}""")).andExpect(status().isOk)
        val open = mvc.perform(post("/api/matches/$id/potm/vote").bearer(coach).jsonBody("""{"nomineeId":"${ids["Tunde"]}"}"""))
            .andExpect(status().isOk).andReturn()
        val potm = parse(open.response.contentAsString)
        assertEquals(2, potm["votesCast"].asInt())
        assertTrue(potm.absent("tally"))
        assertFalse(potm["nominees"].any { it["memberId"].asText() == ids["Chinedu"].toString() }, "you don't see yourself")

        mvc.perform(post("/api/matches/$id/potm/close").bearer(coach)).andExpect(status().isOk)
            .andExpect(jsonPath("$.winners[0].name").value("Tunde A."))
            .andExpect(jsonPath("$.winners[0].votes").value(2))
        mvc.perform(post("/api/matches/$id/potm/vote").bearer(tunde).jsonBody("""{"nomineeId":"${ids["Kola"]}"}""")).andExpect(status().isConflict)

        // Opinions: up to 3 tags, anonymous to players, named for the coach.
        mvc.perform(put("/api/matches/$id/opinion").bearer(tunde).jsonBody("""{"commendTags":["Pressing","Shape","Passing","Fitness"]}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(put("/api/matches/$id/opinion").bearer(tunde).jsonBody("""{"commendTags":["Pressing"],"critiqueTags":["Finishing"],"critiqueText":"Too many chances missed","selfRating":7}"""))
            .andExpect(status().isOk)
        mvc.perform(put("/api/matches/$id/opinion").bearer(kola).jsonBody("""{"commendTags":["Pressing","Shape"]}""")).andExpect(status().isOk)
        mvc.perform(put("/api/matches/$id/opinion").bearer(ola).jsonBody("""{"commendTags":["Pressing"]}""")).andExpect(status().isForbidden)

        val forKola = detail(id, kola)["opinions"]
        assertEquals(2, forKola["count"].asInt())
        assertEquals("Pressing", forKola["tags"][0]["tag"].asText())
        assertEquals(2, forKola["tags"][0]["praised"].asInt())
        assertTrue(forKola["items"].all { it.absent("authorName") && (it.absent("selfRating") || it["commendTags"].size() == 2) })
        val forCoach = detail(id, coach)["opinions"]
        assertTrue(forCoach["items"].any { it["authorName"].asText() == "Tunde Adeyemi" && it["selfRating"].asInt() == 7 })
        assertTrue(detail(id, tunde)["match"]["gaveOpinion"].asBoolean())

        // Records and the public results.
        val record = parse(mvc.perform(get("/api/matches/me/record").bearer(tunde)).andReturn().response.contentAsString)
        assertEquals(1, record["played"].asInt())
        assertEquals(2, record["goals"].asInt())
        assertEquals(1, record["potm"].asInt())
        val amaka = parse(mvc.perform(get("/api/matches/record/${ids["Amaka"]}").bearer(coach)).andReturn().response.contentAsString)
        assertEquals(1, amaka["assists"].asInt())
        val kolaRecord = parse(mvc.perform(get("/api/matches/me/record").bearer(kola)).andReturn().response.contentAsString)
        assertEquals(1, kolaRecord["cleanSheets"].asInt())

        val pub = parse(mvc.perform(get("/api/public/matches")).andExpect(status().isOk).andReturn().response.contentAsString)
        assertTrue(pub.absent("next"))
        assertEquals(listOf("Tunde ×2", "Own goal"), pub["results"][0]["scorers"].map { it.asText() })
        assertEquals("Tunde", pub["results"][0]["potm"][0].asText())

        // Corrections are allowed and logged; the vote window doesn't restart.
        mvc.perform(put("/api/matches/$id/result").bearer(coach).jsonBody(
            """{"ourScore":3,"theirScore":1,"appearances":[$apps],"goals":[${goal("Tunde", "Amaka")},${goal("Tunde", null)},${goal("Amaka", null)}]}""",
        )).andExpect(status().isOk).andExpect(jsonPath("$.potm.open").value(false))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'match.result_corrected'", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM collection WHERE type = 'MATCH_FEE'", Int::class.java), "fee collection only once")
    }

    @Test
    fun `result can't be entered before match day`() {
        val id = create(2)
        mvc.perform(put("/api/matches/$id/result").bearer(coach).jsonBody("""{"ourScore":0,"theirScore":0,"appearances":[${appearance("Kola")}]}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("too_early"))
    }

    @Test
    fun `position fit follows favoured, other, same line, elsewhere`() {
        val cb = ng.cvgfc.api.profile.Position.CB
        assertEquals(100, SelectionService.positionFit(cb, cb, emptyList()))
        assertEquals(70, SelectionService.positionFit(cb, ng.cvgfc.api.profile.Position.CM, listOf("CB")))
        assertEquals(50, SelectionService.positionFit(cb, ng.cvgfc.api.profile.Position.LB, emptyList()))
        assertEquals(30, SelectionService.positionFit(cb, ng.cvgfc.api.profile.Position.ST, emptyList()))
        assertEquals(Factor.entries.sumOf { it.defaultWeight }, 100)
        assertEquals(11, Formations.all.size)
        assertTrue(Formations.all.all { f -> f.slots[0].position == ng.cvgfc.api.profile.Position.GK && f.slots.all { it.y in 10..95 } })
    }
}
