package ng.cvgfc.api.common

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException

/**
 * A failure the client can act on. [code] is a stable machine-readable key
 * (e.g. "jersey_taken"); the message is short, plain English for the UI.
 */
class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val fields: Map<String, String>? = null,
) : RuntimeException(message) {
    companion object {
        fun notFound(what: String) = ApiException(HttpStatus.NOT_FOUND, "not_found", "$what not found.")
        fun badRequest(code: String, message: String) = ApiException(HttpStatus.BAD_REQUEST, code, message)
        fun conflict(code: String, message: String) = ApiException(HttpStatus.CONFLICT, code, message)
    }
}

data class ErrorBody(val code: String, val message: String, val fields: Map<String, String>? = null)

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = ResponseEntity.status(e.status).body(ErrorBody(e.code, e.message ?: "", e.fields))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<ErrorBody> {
        val fields = linkedMapOf<String, String>()
        e.bindingResult.fieldErrors.forEach { fields.putIfAbsent(it.field, it.defaultMessage ?: "Invalid.") }
        return ResponseEntity.badRequest().body(ErrorBody("invalid", "Check the form.", fields))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun unreadable(e: Exception) = ResponseEntity.badRequest().body(ErrorBody("bad_request", "Bad request."))

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooBig(e: MaxUploadSizeExceededException) =
        ResponseEntity.badRequest().body(ErrorBody("photo_too_big", "Photo is too big. Try another."))

    @ExceptionHandler(AccessDeniedException::class)
    fun denied(e: AccessDeniedException) =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorBody("forbidden", "You can't do that."))

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(e: DataIntegrityViolationException): ResponseEntity<ErrorBody> {
        log.warn("Integrity violation: {}", e.mostSpecificCause.message)
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorBody("conflict", "That clashes with an existing record."))
    }
}
