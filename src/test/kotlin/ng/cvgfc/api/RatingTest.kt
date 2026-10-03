package ng.cvgfc.api

import ng.cvgfc.api.match.SelectionService
import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.member.Role
import ng.cvgfc.api.money.CLUB_ZONE
import ng.cvgfc.api.rating.Attribute
import ng.cvgfc.api.rating.Block
import ng.cvgfc.api.rating.CardMath
import ng.cvgfc.api.rating.RatingService
import ng.cvgfc.api.rating.Tier
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingTest : IntegrationTest() {

    @Autowired lateinit var ratingService: RatingService

    private val people = listOf(
        Triple("Chinedu Okafor", "0806 555 0110", "CB"),
        Triple("Tunde Adeyemi", "0803 555 0192", "ST"),
        Triple("Kola Bello", "0807 555 0155", "GK"),
        Triple("Femi Adebayo", "0808 555 0177", "CB"),
        Triple("Sani Musa", "0809 555 0188", "LB"),
        Triple("Amaka Nwosu", "0805 555 0134", "CM"),
        Triple("Bayo Ade", "0812 555 0111", "RM"),
    )
    private lateinit var ids: Map<String, UUID>
    private lateinit var tokens: Map<String, String>
    private val attack = setOf("FIN", "PAC", "DRI", "MOV", "CMP", "SHO")

    @BeforeEach
    fun squad() {
        jdbc.update("""UPDATE stat_set SET attrs = CASE position_group
            WHEN 'GK' THEN '["REF","HAN","GPO","DIS","COM","OVO"]'::jsonb WHEN 'DEF' THEN '["TAC","MRK","AER","PAC","STR","PAS"]'::jsonb
            WHEN 'MID' THEN '["PAS","VIS","CTL","DRI","STA","TAC"]'::jsonb ELSE '["FIN","PAC","DRI","MOV","CMP","SHO"]'::jsonb END""")
        ids = people.mapIndexed { i, (name, phone, pos) ->
            val m = createMember(name, phone, if (i == 0) setOf(Role.COACH) else emptySet(), jersey = (i + 2).toShort())
            jdbc.update("INSERT INTO member_profile (member_id, favoured_position, other_positions, consent_public) VALUES (?, ?, ?::jsonb, ?)",
                m.id, pos, if (pos == "CB") """["ST"]""" else "[]", name.startsWith("Tunde"))
            name.split(" ")[0] to m.id
        }.toMap()
        tokens = people.associate { (name, phone, _) -> name.split(" ")[0] to signIn(phone) }
    }

    private fun t(name: String) = tokens.getValue(name)

    private fun openWindow(days: Int = 7): String {
        val res = mvc.perform(post("/api/ratings/windows").bearer(t("Chinedu")).jsonBody("""{"days":$days}""")).andExpect(status().isCreated).andReturn()
        return parse(res.response.contentAsString)["id"].asText()
    }

    /** Everyone scores attacking attributes Strong (8) and the rest Good (6); themselves Elite (10) in attack. */
    private fun rateEveryone() {
        for ((rater, _) in tokens) {
            for ((ratee, id) in ids) {
                val scores = Attribute.entries.associate { a -> a.name to if (a.name in attack) (if (rater == ratee) 10 else 8) else 6 }
                mvc.perform(put("/api/ratings/me/$id").bearer(t(rater)).jsonBody(mapOf("scores" to scores))).andExpect(status().isOk)
            }
        }
    }

    @Test
    fun `card maths follows the plan`() {
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8, 9), CardMath.trim(listOf(10, 1, 2, 3, 4, 5, 6, 7, 8, 9)), "10 scores: drop one at each end")
        assertEquals(listOf(4, 6, 8), CardMath.trim(listOf(4, 6, 8)), "under 8: keep all")
        assertEquals(8.153846, CardMath.stat(List(6) { 8 }, 10)!!, 0.0001, "self at half weight")
        assertEquals(8.0, CardMath.stat(List(6) { 8 }, null)!!, 0.0001)
        assertNull(CardMath.stat(emptyList(), 10), "self alone doesn't make a stat")
        assertEquals(30, CardMath.toCard(2.0))
        assertEquals(99, CardMath.toCard(10.0))
        assertEquals(Tier.BRONZE, CardMath.tier(64))
        assertEquals(Tier.SILVER, CardMath.tier(65))
        assertEquals(Tier.GOLD, CardMath.tier(84))
        assertEquals(Tier.ELITE, CardMath.tier(85))
        assertNull(CardMath.ovr(listOf(60, null, 70)))
        assertEquals(20, Attribute.entries.size)
    }

    @Test
    fun `a window is opened by the coach, one at a time, and shows each player's own block first`() {
        mvc.perform(post("/api/ratings/windows").bearer(t("Tunde")).jsonBody("{}")).andExpect(status().isForbidden)
        openWindow()
        mvc.perform(post("/api/ratings/windows").bearer(t("Chinedu")).jsonBody("{}")).andExpect(status().isConflict)

        val mine = parse(mvc.perform(get("/api/ratings/me").bearer(t("Tunde"))).andReturn().response.contentAsString)
        assertEquals(7, mine["total"].asInt())
        assertTrue(mine["players"][0]["isMe"].asBoolean(), "yourself first")
        assertEquals("Round 1", mine["window"]["title"].asText())

        val sheet = parse(mvc.perform(get("/api/ratings/me/${ids["Kola"]}").bearer(t("Tunde"))).andReturn().response.contentAsString)
        assertEquals(Block.GOALKEEPING.name, sheet["blocks"][0]["block"].asText(), "keeper: goalkeeping first")
        assertEquals(20, sheet["blocks"].sumOf { it["attrs"].size() })

        mvc.perform(put("/api/ratings/me/${ids["Kola"]}").bearer(t("Tunde")).jsonBody("""{"scores":{"REF":7}}""")).andExpect(status().isBadRequest)
        mvc.perform(put("/api/ratings/me/${ids["Kola"]}").bearer(t("Tunde")).jsonBody("""{"scores":{"REF":8,"FIN":0}}""")).andExpect(status().isOk)
            .andExpect(jsonPath("$.blocks[0].attrs[0].score").value(8))
        val after = parse(mvc.perform(get("/api/ratings/me").bearer(t("Tunde"))).andReturn().response.contentAsString)
        assertEquals(2, after["players"].first { it["memberId"].asText() == ids["Kola"].toString() }["answered"].asInt(), "don't know counts as answered")

        createMember("Trial Guy", "0815 555 0144", status = MemberStatus.TRIALIST)
        val trialist = signIn("0815 555 0144")
        mvc.perform(put("/api/ratings/me/${ids["Kola"]}").bearer(trialist).jsonBody("""{"scores":{"REF":8}}""")).andExpect(status().isForbidden)
    }

    @Test
    fun `closing the window makes cards, published with enough peers, own view shows self`() {
        val window = openWindow()
        rateEveryone()
        mvc.perform(get("/api/ratings/windows").bearer(t("Chinedu"))).andExpect(jsonPath("$[0].finished").value(7))
        mvc.perform(post("/api/ratings/windows/$window/close").bearer(t("Chinedu"))).andExpect(status().isOk)
            .andExpect(jsonPath("$.cards").value(7))

        val mine = parse(mvc.perform(get("/api/cards/me").bearer(t("Tunde"))).andReturn().response.contentAsString)["latest"]
        assertEquals(82, mine["ovr"].asInt(), "(6×8 + ½×10) ÷ 6.5 = 8.15 → 82")
        assertEquals("GOLD", mine["tier"].asText())
        assertTrue(mine["published"].asBoolean())
        assertEquals(listOf("FIN", "PAC", "DRI", "MOV", "CMP", "SHO"), mine["stats"].map { it["label"].asText() })
        assertEquals(100, mine["stats"][0]["self"].asInt(), "own view has the self rating")
        assertEquals(6, mine["stats"][0]["peers"].asInt())

        val squad = parse(mvc.perform(get("/api/cards").bearer(t("Kola"))).andReturn().response.contentAsString)
        val femi = squad.first { it["memberId"].asText() == ids["Femi"].toString() }
        assertEquals("DEF", femi["group"].asText())
        assertEquals(64, femi["ovr"].asInt(), "DEF: pace 82 + five 60s")
        assertEquals("ATT", femi["bestGroup"].asText(), "rated best in attack")
        assertTrue(squad.all { c -> c["stats"].all { it["self"] == null || it["self"].isNull } }, "nobody sees others' self ratings")

        // Selection now uses the card OVR for the slot's group.
        val date = LocalDate.now(CLUB_ZONE).plusDays(2)
        val match = parse(mvc.perform(post("/api/matches").bearer(t("Chinedu")).jsonBody("""{"opponent":"X","date":"$date","kickoff":"16:00","venue":"Y","teamSize":5}"""))
            .andReturn().response.contentAsString)["id"].asText()
        val sel = parse(mvc.perform(get("/api/matches/$match/selection").bearer(t("Chinedu"))).andReturn().response.contentAsString)
        assertFalse(sel["unavailableFactors"].any { it.asText() == "OVR" })
        assertEquals(82, sel["candidates"].first { it["memberId"].asText() == ids["Tunde"].toString() }["ovrs"]["ATT"].asInt())
        assertEquals(100 * (82 - 30) / 69, SelectionService.ovrScore(82))

        // Public site: only members who agreed (Tunde).
        val pub = parse(mvc.perform(get("/api/public/squad")).andExpect(status().isOk).andReturn().response.contentAsString)
        assertEquals(1, pub.size())
        assertEquals("Tunde A.", pub[0]["name"].asText())
        mvc.perform(get("/api/public/squad/${ids["Femi"]}/photo")).andExpect(status().isNotFound)
    }

    @Test
    fun `too few peer ratings means no published card`() {
        val window = openWindow()
        // Only three people rate Kola.
        for (rater in listOf("Tunde", "Femi", "Sani")) {
            mvc.perform(put("/api/ratings/me/${ids["Kola"]}").bearer(t(rater)).jsonBody(mapOf("scores" to Attribute.entries.associate { it.name to 8 })))
        }
        mvc.perform(post("/api/ratings/windows/$window/close").bearer(t("Chinedu")))
        val card = parse(mvc.perform(get("/api/cards/me").bearer(t("Kola"))).andReturn().response.contentAsString)["latest"]
        assertFalse(card["published"].asBoolean())
        val seen = parse(mvc.perform(get("/api/cards/${ids["Kola"]}").bearer(t("Tunde"))).andReturn().response.contentAsString)
        assertFalse(seen["published"].asBoolean())
        assertTrue(seen["stats"].isEmpty, "unpublished cards show no numbers to others")
    }

    @Test
    fun `stat sets can be swapped without a re-vote`() {
        val window = openWindow()
        rateEveryone()
        mvc.perform(post("/api/ratings/windows/$window/close").bearer(t("Chinedu")))
        mvc.perform(put("/api/ratings/stat-sets/ATT").bearer(t("Chinedu")).jsonBody("""{"attrs":["FIN","PAC"]}""")).andExpect(status().isBadRequest)
        mvc.perform(put("/api/ratings/stat-sets/ATT").bearer(t("Tunde")).jsonBody("""{"attrs":["FIN","PAC","DRI","MOV","CMP","STA"]}""")).andExpect(status().isForbidden)
        mvc.perform(put("/api/ratings/stat-sets/ATT").bearer(t("Chinedu")).jsonBody("""{"attrs":["FIN","PAC","DRI","MOV","CMP","STA"]}""")).andExpect(status().isOk)
        val card = parse(mvc.perform(get("/api/cards/me").bearer(t("Tunde"))).andReturn().response.contentAsString)["latest"]
        assertEquals("STA", card["stats"][5]["label"].asText())
        assertEquals(78, card["ovr"].asInt(), "five 82s and a 60")
    }

    @Test
    fun `next round pre-fills last answers, and windows close on their end date`() {
        val first = openWindow()
        mvc.perform(put("/api/ratings/me/${ids["Kola"]}").bearer(t("Tunde")).jsonBody("""{"scores":{"REF":8,"HAN":0}}"""))
        jdbc.update("UPDATE rating_window SET closes_at = now() - interval '1 minute' WHERE id = ?::uuid", first)
        mvc.perform(get("/api/ratings/me").bearer(t("Tunde"))).andExpect(status().isNoContent)
        assertEquals(1, tx.execute { ratingService.closeExpired() })
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM player_card", Int::class.java))

        openWindow()
        val sheet = parse(mvc.perform(get("/api/ratings/me/${ids["Kola"]}").bearer(t("Tunde"))).andReturn().response.contentAsString)
        val gk = sheet["blocks"][0]["attrs"]
        assertEquals(8, gk[0]["score"].asInt())
        assertTrue(gk[0]["prefilled"].asBoolean())
        assertEquals(0, gk[1]["score"].asInt(), "don't know carries over too")
        assertEquals("Round 2", parse(mvc.perform(get("/api/ratings/me").bearer(t("Tunde"))).andReturn().response.contentAsString)["window"]["title"].asText())
    }
}
