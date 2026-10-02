package ng.cvgfc.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.util.UUID

@ConfigurationProperties(prefix = "cvg")
data class CvgProperties(
    val clubId: UUID,
    val bootstrap: Bootstrap = Bootstrap(),
    val auth: Auth = Auth(),
    val sms: Sms = Sms(),
    val cors: Cors = Cors(),
) {
    data class Bootstrap(val adminPhone: String? = null, val adminName: String? = null)

    data class Auth(
        val otpTtl: Duration = Duration.ofMinutes(10),
        val otpMaxAttempts: Int = 5,
        val otpMaxRequestsPerHour: Int = 5,
        val sessionTtl: Duration = Duration.ofDays(60),
        val cookieName: String = "cvg_session",
        val cookieDomain: String? = null,
        val cookieSecure: Boolean = true,
    )

    data class Sms(val provider: String = "log", val termii: Termii = Termii()) {
        data class Termii(
            val baseUrl: String = "https://api.ng.termii.com",
            val apiKey: String? = null,
            val senderId: String = "CVG FC",
        )
    }

    data class Cors(val allowedOrigins: List<String> = emptyList())
}

@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
