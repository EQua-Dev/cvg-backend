package ng.cvgfc.api.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberService
import ng.cvgfc.api.member.MemberView
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

data class SignInRequest(
    @field:NotBlank(message = "Enter your phone number.") val phone: String,
    @field:NotBlank(message = "Enter your passcode.") val passcode: String,
)

data class ChangePasscodeRequest(
    @field:NotBlank(message = "Enter your current passcode.") val currentPasscode: String,
    @field:NotBlank(message = "Enter a new passcode.") val newPasscode: String,
)

/** The signed-in member plus whether they should be nudged to set their own passcode. */
data class Me(val member: MemberView, val usesDefaultPasscode: Boolean) {
    companion object {
        fun of(m: Member) = Me(MemberView.of(m, includePhone = true), m.usesDefaultPasscode)
    }
}

/** [token] is for non-browser clients; web apps use the HttpOnly cookie set alongside. */
data class SignInResponse(val token: String, val member: MemberView, val usesDefaultPasscode: Boolean)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val auth: AuthService,
    private val members: MemberService,
    private val properties: CvgProperties,
) {

    @PostMapping("/sign-in")
    fun signIn(@Valid @RequestBody req: SignInRequest, request: HttpServletRequest): ResponseEntity<SignInResponse> {
        val signedIn = auth.signIn(req.phone, req.passcode, request.getHeader(HttpHeaders.USER_AGENT))
        val m = signedIn.member
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(signedIn.token, properties.auth.sessionTtl).toString())
            .body(SignInResponse(signedIn.token, MemberView.of(m, includePhone = true), m.usesDefaultPasscode))
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal me: CurrentMember) = Me.of(members.get(me.id))

    @PutMapping("/passcode")
    fun changePasscode(
        @Valid @RequestBody req: ChangePasscodeRequest,
        @AuthenticationPrincipal me: CurrentMember,
    ): ResponseEntity<Void> {
        auth.changePasscode(me, req.currentPasscode, req.newPasscode)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/sign-out")
    fun signOut(@AuthenticationPrincipal me: CurrentMember): ResponseEntity<Void> {
        auth.signOut(me.sessionId)
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
            .build()
    }

    private fun sessionCookie(value: String, maxAge: Duration) = sessionCookie(properties, value, maxAge)
}

/** The HttpOnly session cookie the web apps use. */
fun sessionCookie(properties: CvgProperties, value: String, maxAge: Duration): ResponseCookie {
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
