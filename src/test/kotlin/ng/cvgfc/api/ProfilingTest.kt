package ng.cvgfc.api

import ng.cvgfc.api.profile.Position
import ng.cvgfc.api.profiling.Questionnaire
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilingTest : IntegrationTest() {

    @Autowired lateinit var questionnaire: Questionnaire

    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        createMember("Kola Bello", "0807 555 0155")
        token = signIn("0807 555 0155")
    }

    // ---- scoring (no HTTP) ----

    @Test
    fun `each position group sees 6 common plus its own section`() {
        assertEquals(15, questionnaire.questionsFor(Position.ST).size) // 7 common incl. unscored + 8
        assertEquals(15, questionnaire.questionsFor(Position.GK).size)
        val cb = questionnaire.questionsFor(Position.CB).map { it.id }
        val lb = questionnaire.questionsFor(Position.LB).map { it.id }
        assertTrue("D4CB" in cb && "D4" !in cb)
        assertTrue("D4" in lb && "D4CB" !in lb)
    }

    @Test
    fun `a counter-attacking runner gets that label`() {
        // Every answer leans CTR and RUN where possible.
        val answers = mapOf(
            "A1" to "b", "A2" to "b", "A3" to "d", "A4" to "c", "A5" to "b", "A6" to "c", "A7" to "b",
            "T1" to "d", "T2" to "a", "T3" to "c", "T4" to "d", "T5" to "a", "T6" to "c", "T7" to "a", "T8" to "b",
        )
        val r = questionnaire.score(Position.ST, answers)
        assertEquals("CTR", r.topPlan)
        assertEquals("RUN", r.mainRole?.code)
        assertEquals("Counter-attacking Runner in Behind", r.label)
        assertEquals("Counter", r.coachPick)
        assertFalse(r.lowConfidence)
        assertTrue(r.planFits.getValue("CTR") > r.planFits.getValue("POS"))
        assertTrue(r.planFits.values.all { it in 0..100 })
    }

    @Test
    fun `contradicting the consistency pair lowers confidence`() {
        // A1 = keep the ball (POS) but A5 = happy to defend deep (BLK).
        val answers = mapOf(
            "A1" to "a", "A2" to "a", "A3" to "c", "A4" to "b", "A5" to "a", "A6" to "a",
            "M1" to "a", "M2" to "a", "M3" to "c", "M4" to "a", "M5" to "c", "M6" to "d", "M7" to "a", "M8" to "b",
        )
        val r = questionnaire.score(Position.CM, answers)
        assertTrue(r.lowConfidence)
        assertEquals("DLP", r.mainRole?.code)
        assertNull(r.coachPick) // A7 is optional
    }

    @Test
    fun `the secondary role shows when it is close to the main one`() {
        val answers = mapOf(
            "A1" to "a", "A2" to "a", "A3" to "a", "A4" to "a", "A5" to "c", "A6" to "a",
            "G1" to "c", "G2" to "b", "G3" to "c", "G4" to "a", "G5" to "b", "G6" to "c", "G7" to "a", "G8" to "b",
        )
        val r = questionnaire.score(Position.GK, answers)
        assertNotNull(r.mainRole)
        assertNotNull(r.secondaryRole)
    }

    // ---- API ----

    @Test
    fun `players need a main position first`() {
        mvc.perform(get("/api/me/profiling/questionnaire").bearer(token))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("no_position"))
    }

    @Test
    fun `questionnaire is served without points, scored on submit, and kept`() {
        mvc.perform(put("/api/me/profile").bearer(token).jsonBody("""{"favouredPosition":"ST"}"""))

        val body = mvc.perform(get("/api/me/profiling/questionnaire").bearer(token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.group").value("ATT"))
            .andExpect(jsonPath("$.title").value("How do you play?"))
            .andExpect(jsonPath("$.questions.length()").value(15))
            .andReturn().response.contentAsString
        kotlin.test.assertFalse(body.contains("points"))

        mvc.perform(get("/api/me/profiling").bearer(token)).andExpect(status().isNoContent)

        val answers = """{"A1":"b","A2":"b","A3":"d","A4":"c","A5":"b","A6":"c",
            "T1":"d","T2":"a","T3":"c","T4":"d","T5":"a","T6":"c","T7":"a","T8":"b"}"""
        mvc.perform(post("/api/me/profiling").bearer(token).jsonBody("""{"version":1,"answers":$answers}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.label").value("Counter-attacking Runner in Behind"))

        mvc.perform(get("/api/me/profiling").bearer(token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result.mainRole.name").value("Runner in Behind"))
            .andExpect(jsonPath("$.result.planFits.CTR").isNumber)
    }

    @Test
    fun `missing answers and old versions are refused`() {
        mvc.perform(put("/api/me/profile").bearer(token).jsonBody("""{"favouredPosition":"ST"}"""))
        mvc.perform(post("/api/me/profiling").bearer(token).jsonBody("""{"version":1,"answers":{"A1":"a"}}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("incomplete"))
        mvc.perform(post("/api/me/profiling").bearer(token).jsonBody("""{"version":0,"answers":{}}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("questionnaire_changed"))
    }
}
