package ng.cvgfc.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ng.cvgfc.api.auth.SessionAuthenticationFilter
import ng.cvgfc.api.member.CreateMemberRequest
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberService
import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.member.Role
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var tx: TransactionTemplate

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            "TRUNCATE potm_vote, match_opinion, match_card, match_goal, match_appearance, lineup_slot, match_availability, match, audit_event, auth_session, season, member_role, member, onboarding_link, " +
                "member_profile, media_asset, profiling_response, ledger_receipt, ledger_entry, collection_member, collection, " +
                "attendance_mark, session_availability, training_session, training_pattern CASCADE",
        )
    }

    fun createMember(
        name: String,
        phone: String,
        roles: Set<Role> = emptySet(),
        jersey: Short? = null,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): Member = tx.execute {
        memberService.create(
            CreateMemberRequest(fullName = name, phone = phone, jerseyNumber = jersey, status = status, roles = roles),
            actorId = null,
        )
    }!!

    /** Signs in with the default passcode (last 4 digits of the phone) and returns the bearer token. */
    fun signIn(phone: String, passcode: String = phone.filter { it.isDigit() }.takeLast(4)): String {
        val result = mvc.perform(post("/api/auth/sign-in").jsonBody("""{"phone":"$phone","passcode":"$passcode"}"""))
            .andReturn()
        check(result.response.status == 200) { "Sign-in failed: ${result.response.contentAsString}" }
        return json.readTree(result.response.contentAsString)["token"].asText()
    }

    fun MockHttpServletRequestBuilder.bearer(token: String) = header("Authorization", "Bearer $token")

    fun MockHttpServletRequestBuilder.jsonBody(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(if (body is String) body else json.writeValueAsString(body))

    fun MockHttpServletRequestBuilder.client() = header(SessionAuthenticationFilter.CLIENT_HEADER, "test")

    fun parse(content: String): JsonNode = json.readTree(content)
}
