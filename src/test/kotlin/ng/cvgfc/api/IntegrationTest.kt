package ng.cvgfc.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ng.cvgfc.api.auth.SessionAuthenticationFilter
import ng.cvgfc.api.auth.sms.SmsSender
import ng.cvgfc.api.member.CreateMemberRequest
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberService
import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.member.Role
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList

/** Records outgoing texts so tests can read the sign-in code. */
class CapturingSmsSender : SmsSender {
    data class Sent(val phone: String, val message: String)

    val sent = CopyOnWriteArrayList<Sent>()

    override fun send(phoneE164: String, message: String) {
        sent += Sent(phoneE164, message)
    }

    fun lastCodeFor(phoneE164: String): String =
        sent.last { it.phone == phoneE164 }.message.take(6)
}

@TestConfiguration
class TestSmsConfig {
    @Bean
    fun smsSender() = CapturingSmsSender()
}

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSmsConfig::class)
abstract class IntegrationTest {

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var sms: CapturingSmsSender
    @Autowired lateinit var memberService: MemberService
    @Autowired lateinit var tx: TransactionTemplate

    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute("TRUNCATE audit_event, auth_session, otp_challenge, season, member_role, member CASCADE")
        sms.sent.clear()
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

    /** Signs in through the real code flow and returns the bearer token. */
    fun signIn(phone: String): String {
        mvc.perform(post("/api/auth/code").contentType(MediaType.APPLICATION_JSON).content("""{"phone":"$phone"}"""))
        val e164 = ng.cvgfc.api.common.PhoneNumbers.require(phone)
        val result = mvc.perform(
            post("/api/auth/verify").contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"$phone","code":"${sms.lastCodeFor(e164)}"}"""),
        ).andReturn()
        return json.readTree(result.response.contentAsString)["token"].asText()
    }

    fun MockHttpServletRequestBuilder.bearer(token: String) = header("Authorization", "Bearer $token")

    fun MockHttpServletRequestBuilder.jsonBody(body: Any) =
        contentType(MediaType.APPLICATION_JSON).content(if (body is String) body else json.writeValueAsString(body))

    fun MockHttpServletRequestBuilder.client() = header(SessionAuthenticationFilter.CLIENT_HEADER, "test")

    fun parse(content: String): JsonNode = json.readTree(content)
}
