package ng.cvgfc.api.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.MemberService
import ng.cvgfc.api.member.MemberView
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

data class CodeRequest(@field:NotBlank(message = "Enter your phone number.") val phone: String)

data class CodeSent(val expiresInSeconds: Long)

data class VerifyRequest(
    @field:NotBlank(message = "Enter your phone number.") val phone: String,
    @field:Pattern(regexp = "\\d{6}", message = "Enter the 6-digit code.") val code: String,
)

/** [token] is for non-browser clients; web apps use the HttpOnly cookie set alongside. */
data class SignInResponse(val token: String, val member: MemberView)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val auth: AuthService,
    private val members: MemberService,
    private val properties: CvgProperties,
) {

    @PostMapping("/code")
    fun requestCode(@Valid @RequestBody req: CodeRequest) = CodeSent(auth.requestCode(req.phone).seconds)

    @PostMapping("/verify")
    fun verify(@Valid @RequestBody req: VerifyRequest, request: HttpServletRequest): ResponseEntity<SignInResponse> {
        val signedIn = auth.verifyCode(req.phone, req.code, request.getHeader(HttpHeaders.USER_AGENT))
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(signedIn.token, properties.auth.sessionTtl).toString())
            .body(SignInResponse(signedIn.token, MemberView.of(signedIn.member, includePhone = true)))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal me: CurrentMember) = MemberView.of(members.get(me.id), includePhone = true)

    @PostMapping("/sign-out")
    fun signOut(@AuthenticationPrincipal me: CurrentMember): ResponseEntity<Void> {
        auth.signOut(me.sessionId)
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
            .build()
    }

    private fun sessionCookie(value: String, maxAge: Duration): ResponseCookie {
        val config = properties.auth
        return ResponseCookie.from(config.cookieName, value)
            .httpOnly(true)
            .secure(config.cookieSecure)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAge)
            .apply { if (!config.cookieDomain.isNullOrBlank()) domain(config.cookieDomain) }
            .build()
    }
}
