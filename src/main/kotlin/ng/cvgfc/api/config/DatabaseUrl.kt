package ng.cvgfc.api.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.net.URI
import java.net.URLDecoder

/**
 * Railway, Render and Heroku hand out `postgresql://user:pass@host:port/db`. Spring wants a JDBC URL
 * plus separate credentials, so turn the first into the second. A `jdbc:` URL is left alone.
 */
class DatabaseUrlPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val raw = environment.getProperty("DATABASE_URL")?.trim().orEmpty()
        val parsed = parse(raw) ?: return
        environment.propertySources.addFirst(MapPropertySource("databaseUrl", parsed))
    }

    companion object {
        fun parse(raw: String): Map<String, Any>? {
            if (!raw.startsWith("postgres://") && !raw.startsWith("postgresql://")) return null
            val uri = URI(raw.replaceFirst(Regex("^postgres(ql)?://"), "http://"))
            val (user, pass) = (uri.rawUserInfo ?: "").split(":", limit = 2).map { URLDecoder.decode(it, Charsets.UTF_8) }.let {
                it.getOrElse(0) { "" } to it.getOrElse(1) { "" }
            }
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return buildMap {
                put("spring.datasource.url", "jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query")
                if (user.isNotEmpty()) put("spring.datasource.username", user)
                if (pass.isNotEmpty()) put("spring.datasource.password", pass)
            }
        }
    }
}
