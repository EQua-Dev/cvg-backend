package ng.cvgfc.api

import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.member.Role
import ng.cvgfc.api.money.CollectionService
import ng.cvgfc.api.money.naira
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyTest : IntegrationTest() {

    @Autowired lateinit var collectionService: CollectionService

    private lateinit var treasurer: String
    private lateinit var player: String
    private lateinit var tundeId: UUID
    private lateinit var kolaId: UUID
    private lateinit var femiId: UUID

    @BeforeEach
    fun squad() {
        tundeId = createMember("Tunde Adeyemi", "0803 555 0192", jersey = 9).id
        createMember("Amaka Nwosu", "0805 555 0134", setOf(Role.TREASURER))
        kolaId = createMember("Kola Bello", "0807 555 0155", jersey = 7).id
        femiId = createMember("Femi Adebayo", "0808 555 0166", status = MemberStatus.TRIALIST).id
        treasurer = signIn("0805 555 0134")
        player = signIn("0803 555 0192")
    }

    private fun newCollection(body: String): String =
        parse(mvc.perform(post("/api/collections").bearer(treasurer).jsonBody(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()

    private fun pay(memberId: UUID, collectionId: String?, kobo: Long, extra: String = ""): String =
        parse(mvc.perform(post("/api/ledger/payments").bearer(treasurer).jsonBody(
            """{"memberId":"$memberId"${collectionId?.let { ",\"collectionId\":\"$it\"" } ?: ",\"category\":\"DONATION\""},"amountKobo":$kobo$extra}""",
        )).andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()

    @Test
    fun `a collection tracks paid, partial and unpaid`() {
        // ACTIVE audience: Tunde, Amaka, Kola. Femi is a trialist, so not included.
        val id = newCollection("""{"title":"New jerseys","type":"KIT","amountKobo":800000,"dueDate":"2026-10-31"}""")

        pay(tundeId, id, 800000)
        pay(kolaId, id, 300000, ""","method":"TRANSFER","note":"first half"""")

        mvc.perform(get("/api/collections/$id").bearer(treasurer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.memberCount").value(3))
            .andExpect(jsonPath("$.summary.paidCount").value(1))
            .andExpect(jsonPath("$.summary.partialCount").value(1))
            .andExpect(jsonPath("$.summary.expectedKobo").value(2400000))
            .andExpect(jsonPath("$.summary.collectedKobo").value(1100000))
            // Unpaid first, then partial, then paid.
            .andExpect(jsonPath("$.members[0].fullName").value("Amaka Nwosu"))
            .andExpect(jsonPath("$.members[0].state").value("UNPAID"))
            .andExpect(jsonPath("$.members[1].fullName").value("Kola Bello"))
            .andExpect(jsonPath("$.members[1].owedKobo").value(500000))
            .andExpect(jsonPath("$.members[2].state").value("PAID"))
    }

    @Test
    fun `paying for a collection you weren't on adds you to it`() {
        val id = newCollection("""{"title":"Abuja Cup fee","type":"TOURNAMENT_FEE","amountKobo":500000,"dueDate":"2026-10-20"}""")
        pay(femiId, id, 500000)
        mvc.perform(get("/api/collections/$id").bearer(treasurer)).andExpect(jsonPath("$.summary.memberCount").value(4))
    }

    @Test
    fun `mistakes are reversed, never edited, and the database enforces it`() {
        val id = newCollection("""{"title":"Welfare","type":"WELFARE","amountKobo":200000,"dueDate":"2026-10-31"}""")
        val entry = pay(tundeId, id, 200000)

        mvc.perform(post("/api/ledger/$entry/reverse").bearer(treasurer).jsonBody("""{"reason":"Wrong person"}"""))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.direction").value("OUT"))
            .andExpect(jsonPath("$.reversesId").value(entry))
            .andExpect(jsonPath("$.note").value("Reversal: Wrong person"))

        mvc.perform(get("/api/collections/$id").bearer(treasurer))
            .andExpect(jsonPath("$.summary.collectedKobo").value(0))
            .andExpect(jsonPath("$.summary.paidCount").value(0))

        // Once only, and a correction can't itself be reversed.
        mvc.perform(post("/api/ledger/$entry/reverse").bearer(treasurer).jsonBody("""{"reason":"again"}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("already_reversed"))
        mvc.perform(post("/api/ledger/$entry/reverse").bearer(treasurer).jsonBody("""{}"""))
            .andExpect(status().isConflict)

        // Even direct SQL can't change history.
        val update = runCatching { jdbc.update("UPDATE ledger_entry SET amount_kobo = 1 WHERE id = ?::uuid", entry) }
        val delete = runCatching { jdbc.update("DELETE FROM ledger_entry WHERE id = ?::uuid", entry) }
        assertTrue(update.isFailure && delete.isFailure)
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Int::class.java))
    }

    @Test
    fun `expenses need a note and show as flagged until a receipt is added`() {
        mvc.perform(post("/api/ledger/expenses").bearer(treasurer).jsonBody("""{"category":"FIELD_RENTAL","amountKobo":1200000}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("note_required"))
        mvc.perform(post("/api/ledger/expenses").bearer(treasurer).jsonBody("""{"category":"DONATION","amountKobo":100,"note":"x"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("invalid_category"))

        val expense = parse(mvc.perform(post("/api/ledger/expenses").bearer(treasurer)
            .jsonBody("""{"category":"EQUIPMENT","amountKobo":1800000,"note":"Match balls ×2"}"""))
            .andExpect(jsonPath("$.flagged").value(true))
            .andReturn().response.contentAsString)["id"].asText()

        mvc.perform(multipart("/api/ledger/$expense/receipt").file(MockMultipartFile("file", "r.jpg", "image/jpeg", JPEG))
            .with { it.method = "PUT"; it }.bearer(treasurer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasReceipt").value(true))
            .andExpect(jsonPath("$.flagged").value(false))
        mvc.perform(multipart("/api/ledger/$expense/receipt").file(MockMultipartFile("file", "r.jpg", "image/jpeg", JPEG))
            .with { it.method = "PUT"; it }.bearer(treasurer))
            .andExpect(status().isConflict)
        mvc.perform(get("/api/ledger/$expense/receipt").bearer(treasurer)).andExpect(status().isOk)
        mvc.perform(get("/api/ledger/$expense/receipt").bearer(player)).andExpect(status().isForbidden)
    }

    @Test
    fun `the ledger shows the month, totals and the all-time balance`() {
        pay(tundeId, null, 500000) // donation
        mvc.perform(post("/api/ledger/expenses").bearer(treasurer)
            .jsonBody("""{"category":"FIELD_RENTAL","amountKobo":1200000,"note":"Area 1 field","occurredOn":"2026-09-10"}"""))

        val month = LocalDate.now(ng.cvgfc.api.money.CLUB_ZONE).toString().take(7)
        mvc.perform(get("/api/ledger?month=$month").bearer(treasurer))
            .andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.inKobo").value(500000))
            .andExpect(jsonPath("$.balanceKobo").value(-700000))
            .andExpect(jsonPath("$.entries[0].member.name").value("Tunde Adeyemi"))
            .andExpect(jsonPath("$.entries[0].recordedBy").value("Amaka Nwosu"))
        mvc.perform(get("/api/ledger?month=2026-09").bearer(treasurer))
            .andExpect(jsonPath("$.outKobo").value(1200000))
            .andExpect(jsonPath("$.flaggedCount").value(1))
    }

    @Test
    fun `bad amounts and future dates are refused`() {
        listOf(0L, -5L, 2_000_000_000L).forEach {
            mvc.perform(post("/api/ledger/payments").bearer(treasurer).jsonBody("""{"memberId":"$tundeId","category":"DONATION","amountKobo":$it}"""))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("invalid_amount"))
        }
        mvc.perform(post("/api/ledger/payments").bearer(treasurer)
            .jsonBody("""{"memberId":"$tundeId","category":"DONATION","amountKobo":100,"occurredOn":"2099-01-01"}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("future_date"))
    }

    @Test
    fun `only treasurer and admin handle money, players see their own dues`() {
        mvc.perform(get("/api/collections").bearer(player)).andExpect(status().isForbidden)
        mvc.perform(get("/api/ledger").bearer(player)).andExpect(status().isForbidden)
        mvc.perform(post("/api/ledger/payments").bearer(player).jsonBody("""{"amountKobo":1,"category":"DONATION"}"""))
            .andExpect(status().isForbidden)

        val dues = newCollection("""{"type":"DUES","amountKobo":200000,"dueDate":"2026-10-28","recurring":true}""")
        val cdc = newCollection("""{"title":"CDC 2026","type":"CDC","amountKobo":300000,"dueDate":"2026-10-15"}""")
        pay(tundeId, cdc, 100000)

        mvc.perform(get("/api/me/dues").bearer(player))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.owedKobo").value(400000))
            .andExpect(jsonPath("$.open.length()").value(2))
            .andExpect(jsonPath("$.open[0].title").value("CDC 2026"))
            .andExpect(jsonPath("$.open[0].state").value("PARTIAL"))
            .andExpect(jsonPath("$.open[1].title").value("October 2026 dues"))
            .andExpect(jsonPath("$.history.length()").value(1))
            .andExpect(jsonPath("$.history[0].amountKobo").value(100000))
        assertTrue(dues.isNotEmpty())
    }

    @Test
    fun `monthly dues start themselves on the 1st, once`() {
        newCollection("""{"type":"DUES","amountKobo":200000,"dueDate":"2026-10-28","recurring":true}""")
        mvc.perform(post("/api/collections").bearer(treasurer)
            .jsonBody("""{"title":"x","type":"CDC","amountKobo":1,"dueDate":"2026-10-28","recurring":true}"""))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("recurring_dues_only"))

        assertEquals(0, tx.execute { collectionService.rollRecurring(LocalDate.of(2026, 10, 15)) })
        // A new member joins before November.
        createMember("Sola Adeniyi", "0809 555 0101")
        assertEquals(1, tx.execute { collectionService.rollRecurring(LocalDate.of(2026, 11, 1)) })
        assertEquals(0, tx.execute { collectionService.rollRecurring(LocalDate.of(2026, 11, 2)) })

        val all = parse(mvc.perform(get("/api/collections").bearer(treasurer)).andReturn().response.contentAsString)
        assertEquals("November 2026 dues", all[0]["title"].asText())
        assertEquals("2026-11-28", all[0]["dueDate"].asText())
        assertEquals(4, all[0]["memberCount"].asInt()) // the newcomer is included
    }

    @Test
    fun `members who paid can't be removed and closed collections take no payments`() {
        val id = newCollection("""{"title":"Kit","type":"KIT","amountKobo":800000,"dueDate":"2026-10-31","audience":"SELECTED","memberIds":["$tundeId","$kolaId"]}""")
        pay(tundeId, id, 100000)
        mvc.perform(put("/api/collections/$id/members").bearer(treasurer).jsonBody("""{"memberIds":["$kolaId"]}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("has_paid"))
        mvc.perform(put("/api/collections/$id/members").bearer(treasurer).jsonBody("""{"memberIds":["$tundeId","$femiId"]}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.summary.memberCount").value(2))

        mvc.perform(post("/api/collections/$id/close").bearer(treasurer)).andExpect(jsonPath("$.open").value(false))
        mvc.perform(post("/api/ledger/payments").bearer(treasurer).jsonBody("""{"memberId":"$tundeId","collectionId":"$id","amountKobo":100}"""))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("collection_closed"))
    }

    @Test
    fun `the scheduled job's no-argument entry point works through the Spring proxy`() {
        newCollection("""{"type":"DUES","amountKobo":200000,"dueDate":"2026-10-28","recurring":true}""")
        // Regression: a Kotlin default argument here used to be evaluated on the proxy (null clock).
        tx.execute { collectionService.rollRecurring() }
    }

    @Test
    fun `naira formatting`() {
        assertEquals("₦2,000", naira(200000))
        assertEquals("₦1,250.50", naira(125050))
    }
}
