package ng.cvgfc.api

import ng.cvgfc.api.member.Role
import ng.cvgfc.api.money.CLUB_ZONE
import ng.cvgfc.api.training.SessionKind
import ng.cvgfc.api.training.TrainingService
import ng.cvgfc.api.training.TrainingSession
import ng.cvgfc.api.training.TrainingSessionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrainingTest : IntegrationTest() {

    @Autowired lateinit var sessions: TrainingSessionRepository
    @Autowired lateinit var training: TrainingService

    private lateinit var coach: String
    private lateinit var captain: String
    private lateinit var tunde: String
    private lateinit var tundeId: UUID
    private lateinit var kolaId: UUID
    private val club = UUID.fromString("00000000-0000-0000-0000-00000000c0c0")

    @BeforeEach
    fun squad() {
        createMember("Chinedu Okafor", "0806 555 0110", setOf(Role.COACH), jersey = 4)
        createMember("Amaka Nwosu", "0805 555 0134", setOf(Role.CAPTAIN), jersey = 11)
        tundeId = createMember("Tunde Adeyemi", "0803 555 0192", jersey = 9).id
        kolaId = createMember("Kola Bello", "0807 555 0155", jersey = 7).id
        coach = signIn("0806 555 0110")
        captain = signIn("0805 555 0134")
        tunde = signIn("0803 555 0192")
    }

    private fun session(startsAt: Instant, kind: SessionKind = SessionKind.COMPULSORY): UUID =
        tx.execute { sessions.save(TrainingSession(club, null, null, startsAt, "Area 1", kind, impromptu = false, createdBy = null)).id }!!

    private fun later(hours: Long) = Instant.now().plus(Duration.ofHours(hours))

    private fun marks(id: UUID, token: String, body: String) =
        mvc.perform(put("/api/training/sessions/$id/marks").bearer(token).jsonBody("""{"marks":$body}"""))

    @Test
    fun `a weekly slot creates the next two weeks of sessions, once`() {
        val wednesday = 3
        mvc.perform(post("/api/training/patterns").bearer(coach).jsonBody("""{"weekday":$wednesday,"startTime":"17:30","venue":"Area 1 Field","kind":"COMPULSORY"}"""))
            .andExpect(status().isCreated)
        val count = jdbc.queryForObject("SELECT count(*) FROM training_session", Int::class.java)!!
        assertTrue(count in 2..3, "got $count")
        assertEquals(0, tx.execute { training.generateUpcoming() })

        val list = parse(mvc.perform(get("/api/training/sessions").bearer(tunde)).andReturn().response.contentAsString)
        assertTrue(list.all { LocalDate.parse(it["date"].asText()).dayOfWeek.value == wednesday })
        assertEquals("17:30:00", list[0]["time"].asText())

        val patternId = parse(mvc.perform(get("/api/training/patterns").bearer(coach)).andReturn().response.contentAsString)[0]["id"].asText()
        mvc.perform(delete("/api/training/patterns/$patternId").bearer(coach)).andExpect(status().isNoContent)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM training_session WHERE starts_at > now()", Int::class.java))
        mvc.perform(post("/api/training/patterns").bearer(tunde).jsonBody("""{"weekday":1,"startTime":"07:00","venue":"x"}"""))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `everyone is in by default, out needs a reason, counts update`() {
        val id = session(later(72))
        mvc.perform(get("/api/training/sessions/$id").bearer(tunde))
            .andExpect(jsonPath("$.session.inCount").value(4))
            .andExpect(jsonPath("$.session.me.status").value("IN"))
            .andExpect(jsonPath("$.session.me.locked").value(false))
            .andExpect(jsonPath("$.roster").doesNotExist()) // players don't see the roster

        mvc.perform(put("/api/training/sessions/$id/availability").bearer(tunde).jsonBody("""{"status":"OUT"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("reason_required"))
        mvc.perform(put("/api/training/sessions/$id/availability").bearer(tunde).jsonBody("""{"status":"OUT","reason":"WORK"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inCount").value(3))
            .andExpect(jsonPath("$.outCount").value(1))
            .andExpect(jsonPath("$.me.reason").value("WORK"))

        mvc.perform(get("/api/training/sessions/$id").bearer(captain))
            .andExpect(jsonPath("$.roster[?(@.fullName=='Tunde Adeyemi')].availability").value("OUT"))
    }

    @Test
    fun `availability locks two hours before but the coach can still change it`() {
        val id = session(later(1))
        mvc.perform(put("/api/training/sessions/$id/availability").bearer(tunde).jsonBody("""{"status":"OUT","reason":"SICK"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("availability_locked"))
        mvc.perform(put("/api/training/sessions/$id/availability/$tundeId").bearer(coach).jsonBody("""{"status":"OUT","reason":"SICK"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session.outCount").value(1))
        assertTrue("training.availability_set" in jdbc.queryForList("SELECT action FROM audit_event", String::class.java))
        mvc.perform(put("/api/training/sessions/$id/availability/$kolaId").bearer(tunde).jsonBody("""{"status":"IN"}"""))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `marks wait for the day, the newest tap wins, and closing marks the rest absent`() {
        mvc.perform(put("/api/training/sessions/${session(later(72))}/marks").bearer(captain).jsonBody("""{"marks":[]}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("too_early"))

        val id = session(later(-1))
        val t0 = Instant.now().minusSeconds(120)
        marks(id, captain, """[{"memberId":"$tundeId","mark":"PRESENT","markedAt":"$t0"}]""").andExpect(status().isOk)
        // An older tap from another phone arrives late: ignored.
        marks(id, captain, """[{"memberId":"$tundeId","mark":"ABSENT","markedAt":"${t0.minusSeconds(60)}"}]""")
            .andExpect(jsonPath("$.roster[?(@.fullName=='Tunde Adeyemi')].mark").value("PRESENT"))
        // A newer tap wins; null clears.
        marks(id, captain, """[{"memberId":"$kolaId","mark":"LATE"},{"memberId":"$tundeId","mark":null}]""")
            .andExpect(jsonPath("$.roster[?(@.fullName=='Kola Bello')].mark").value("LATE"))
            .andExpect(jsonPath("$.session.markedCount").value(1))
        marks(id, tunde, "[]").andExpect(status().isForbidden)

        mvc.perform(post("/api/training/sessions/$id/close").bearer(captain))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session.status").value("CLOSED"))
            .andExpect(jsonPath("$.session.markedCount").value(4))
            .andExpect(jsonPath("$.roster[?(@.fullName=='Tunde Adeyemi')].mark").value("ABSENT"))

        // After closing: the captain can't edit; the coach can, and it's audited.
        marks(id, captain, """[{"memberId":"$tundeId","mark":"EXCUSED"}]""").andExpect(status().isForbidden)
        marks(id, coach, """[{"memberId":"$tundeId","mark":"EXCUSED"}]""").andExpect(status().isOk)
        assertTrue("attendance.changed_after_close" in jdbc.queryForList("SELECT action FROM audit_event", String::class.java))
    }

    @Test
    fun `attendance percent, streak, extras and no-shows`() {
        val s1 = session(later(-24 * 6))
        val s2 = session(later(-24 * 4))
        val opt = session(later(-24 * 3), SessionKind.OPTIONAL)
        val s3 = session(later(-24 * 2))
        // Tunde: present, late, (optional present), excused  → 2/2 = 100%, streak 2, 1 extra
        // Kola:  present, absent (didn't say out), present   → 2/3 = 67%, streak 1, 1 no-show
        for ((id, t, k) in listOf(Triple(s1, "PRESENT", "PRESENT"), Triple(s2, "LATE", "ABSENT"), Triple(opt, "PRESENT", null), Triple(s3, "EXCUSED", "PRESENT"))) {
            marks(id, coach, """[{"memberId":"$tundeId","mark":"$t"}${k?.let { ",{\"memberId\":\"$kolaId\",\"mark\":\"$it\"}" } ?: ""}]""")
            mvc.perform(post("/api/training/sessions/$id/close").bearer(coach)).andExpect(status().isOk)
        }

        mvc.perform(get("/api/training/me").bearer(tunde))
            .andExpect(jsonPath("$.stats.percent").value(100))
            .andExpect(jsonPath("$.stats.streak").value(2))
            .andExpect(jsonPath("$.stats.extras").value(1))
            .andExpect(jsonPath("$.stats.late").value(1))
            .andExpect(jsonPath("$.recent.length()").value(4))
            .andExpect(jsonPath("$.recent[0].mark").value("EXCUSED"))

        val squad = parse(mvc.perform(get("/api/training/stats").bearer(coach)).andReturn().response.contentAsString)
        val kola = squad.first { it["fullName"].asText() == "Kola Bello" }
        assertEquals(67, kola["percent"].asInt())
        assertEquals(1, kola["streak"].asInt())
        assertEquals(1, kola["noShows"].asInt())
        assertEquals("Tunde Adeyemi", squad[0]["fullName"].asText()) // best first
        mvc.perform(get("/api/training/stats").bearer(tunde)).andExpect(status().isForbidden)
    }

    @Test
    fun `impromptu sessions and cancelling`() {
        val tomorrow = LocalDate.now(CLUB_ZONE).plusDays(1)
        val id = parse(mvc.perform(post("/api/training/sessions").bearer(coach)
            .jsonBody("""{"date":"$tomorrow","startTime":"07:00","venue":"Garki park","kind":"OPTIONAL","focus":"Fitness"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.impromptu").value(true))
            .andExpect(jsonPath("$.focus").value("Fitness"))
            .andReturn().response.contentAsString)["id"].asText()
        mvc.perform(post("/api/training/sessions/$id/cancel").bearer(coach)).andExpect(jsonPath("$.status").value("CANCELLED"))
        mvc.perform(put("/api/training/sessions/$id/availability").bearer(tunde).jsonBody("""{"status":"IN"}"""))
            .andExpect(status().isConflict)
        mvc.perform(post("/api/training/sessions").bearer(coach)
            .jsonBody("""{"date":"2020-01-01","startTime":"07:00","venue":"x"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("past_date"))
    }
}
