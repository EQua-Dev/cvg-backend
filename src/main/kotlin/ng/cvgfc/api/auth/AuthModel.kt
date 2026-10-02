package ng.cvgfc.api.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import ng.cvgfc.api.member.Role
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/** The signed-in member, as carried in the security context. */
data class CurrentMember(val id: UUID, val sessionId: UUID, val roles: Set<Role>) {
    val isManagement get() = roles.isNotEmpty()
    fun has(role: Role) = role in roles
}

/** A signed-in device. The raw token lives only in the client's cookie; we keep its hash. */
@Entity
@Table(name = "auth_session")
class AuthSession(
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: UUID,
    @Column(name = "token_hash", nullable = false, updatable = false)
    val tokenHash: String,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
    @Column(name = "user_agent", updatable = false)
    val userAgent: String?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null

    @Column(name = "last_seen_at")
    var lastSeenAt: Instant? = null

    fun isLive(now: Instant) = revokedAt == null && expiresAt.isAfter(now)
}

interface AuthSessionRepository : JpaRepository<AuthSession, UUID> {
    fun findByTokenHash(tokenHash: String): AuthSession?
    fun findByMemberIdAndRevokedAtIsNull(memberId: UUID): List<AuthSession>
}
