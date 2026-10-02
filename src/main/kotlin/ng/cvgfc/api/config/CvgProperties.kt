package ng.cvgfc.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Duration
import java.util.UUID

@ConfigurationProperties(prefix = "cvg")
data class CvgProperties(
    val clubId: UUID,
    val bootstrap: Bootstrap = Bootstrap(),
    val auth: Auth = Auth(),
    val cors: Cors = Cors(),
    val links: Links = Links(),
    val onboarding: Onboarding = Onboarding(),
) {
    /** Public URLs of the front ends, used to build links we hand out (WhatsApp, QR codes). */
    data class Links(
        val clubApp: String = "http://localhost:3002",
        val publicSite: String = "http://localhost:3000",
        /** Signs ID-card QR links so member codes can't simply be enumerated. */
        val verifySecret: String = "dev-only-change-me",
    )

    data class Onboarding(val linkTtl: Duration = Duration.ofDays(14))

    data class Bootstrap(val adminPhone: String? = null, val adminName: String? = null)

    data class Auth(
        /** Wrong passcodes allowed before the account is locked for [passcodeLockout]. */
        val passcodeMaxAttempts: Int = 5,
        val passcodeLockout: Duration = Duration.ofMinutes(15),
        val sessionTtl: Duration = Duration.ofDays(60),
        val cookieName: String = "cvg_session",
        val cookieDomain: String? = null,
        val cookieSecure: Boolean = true,
    )

    data class Cors(val allowedOrigins: List<String> = emptyList())
}

@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
