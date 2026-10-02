package ng.cvgfc.api.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates requests from the session cookie (web apps) or a Bearer token.
 *
 * CSRF: cookies are SameSite=Lax, and state-changing requests that rely on the
 * cookie must also carry the [CLIENT_HEADER]. Browsers only allow that custom
 * header cross-origin after a CORS preflight, which our allowlist controls.
 */
class SessionAuthenticationFilter(
    private val auth: AuthService,
    private val cookieName: String,
) : OncePerRequestFilter() {

    companion object {
        const val CLIENT_HEADER = "X-CVG-Client"
        private val SAFE = setOf(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name())
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val bearer = request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
        val cookie = request.cookies?.firstOrNull { it.name == cookieName }?.value
        val token = bearer ?: cookie

        if (token != null) {
            if (bearer == null && request.method !in SAFE && request.getHeader(CLIENT_HEADER) == null) {
                response.status = HttpServletResponse.SC_FORBIDDEN
                response.contentType = MediaType.APPLICATION_JSON_VALUE
                response.writer.write("""{"code":"missing_client_header","message":"Bad request."}""")
                return
            }
            auth.authenticate(token)?.let { me ->
                val authorities = me.roles.map { SimpleGrantedAuthority("ROLE_${it.name}") } +
                    SimpleGrantedAuthority("ROLE_PLAYER")
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(me, null, authorities)
            }
        }
        chain.doFilter(request, response)
    }
}
