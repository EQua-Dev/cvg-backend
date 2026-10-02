package ng.cvgfc.api.auth.sms

import ng.cvgfc.api.config.CvgProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** Sends a text to an E.164 number. */
fun interface SmsSender {
    fun send(phoneE164: String, message: String)
}

/** Development sender: writes the message to the log instead of sending it. */
@Component
@ConditionalOnProperty(prefix = "cvg.sms", name = ["provider"], havingValue = "log", matchIfMissing = true)
class LogSmsSender : SmsSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(phoneE164: String, message: String) {
        log.info("[SMS to {}] {}", phoneE164, message)
    }
}

/** Termii (Nigerian SMS gateway). Uses the DND channel so codes reach DND-registered lines. */
@Component
@ConditionalOnProperty(prefix = "cvg.sms", name = ["provider"], havingValue = "termii")
class TermiiSmsSender(properties: CvgProperties) : SmsSender {

    private val config = properties.sms.termii
    private val client = RestClient.builder().baseUrl(config.baseUrl).build()

    init {
        require(!config.apiKey.isNullOrBlank()) { "cvg.sms.termii.api-key is required when provider=termii" }
    }

    override fun send(phoneE164: String, message: String) {
        client.post()
            .uri("/api/sms/send")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "to" to phoneE164.removePrefix("+"),
                    "from" to config.senderId,
                    "sms" to message,
                    "type" to "plain",
                    "channel" to "dnd",
                    "api_key" to config.apiKey,
                ),
            )
            .retrieve()
            .toBodilessEntity()
    }
}
