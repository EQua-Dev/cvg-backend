package ng.cvgfc.api.audit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import ng.cvgfc.api.config.CvgProperties
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Append-only record of who changed what. Never updated or deleted. */
@Entity
@Table(name = "audit_event")
class AuditEvent(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(name = "actor_id", updatable = false)
    val actorId: UUID?,
    @Column(nullable = false, updatable = false)
    val action: String,
    @Column(name = "entity_type", nullable = false, updatable = false)
    val entityType: String,
    @Column(name = "entity_id", updatable = false)
    val entityId: UUID?,
    @Column(updatable = false)
    val summary: String?,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", updatable = false)
    val beforeState: Map<String, Any?>?,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", updatable = false)
    val afterState: Map<String, Any?>?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) {
    @Id
    val id: UUID = UUID.randomUUID()
}

interface AuditRepository : JpaRepository<AuditEvent, UUID> {
    fun findByClubIdOrderByCreatedAtDesc(clubId: UUID, pageable: Pageable): Page<AuditEvent>
    fun findByClubIdAndEntityTypeOrderByCreatedAtDesc(clubId: UUID, entityType: String, pageable: Pageable): Page<AuditEvent>
}

@Service
class AuditService(
    private val repository: AuditRepository,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    /**
     * Records a change inside the caller's transaction, so the entry and the change
     * it describes are committed (or rolled back) together.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        actorId: UUID?,
        action: String,
        entityType: String,
        entityId: UUID?,
        summary: String?,
        before: Map<String, Any?>? = null,
        after: Map<String, Any?>? = null,
    ) {
        repository.save(
            AuditEvent(properties.clubId, actorId, action, entityType, entityId, summary, before, after, Instant.now(clock)),
        )
    }
}

data class AuditEntry(
    val id: UUID,
    val at: Instant,
    val actorId: UUID?,
    val action: String,
    val entityType: String,
    val entityId: UUID?,
    val summary: String?,
    val before: Map<String, Any?>?,
    val after: Map<String, Any?>?,
)

data class AuditPage(val items: List<AuditEntry>, val page: Int, val size: Int, val total: Long)

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasAnyRole('ADMIN','COACH','TREASURER')")
class AuditController(private val repository: AuditRepository, private val properties: CvgProperties) {

    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) entityType: String?,
    ): AuditPage {
        val request = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 200))
        val result = if (entityType == null) {
            repository.findByClubIdOrderByCreatedAtDesc(properties.clubId, request)
        } else {
            repository.findByClubIdAndEntityTypeOrderByCreatedAtDesc(properties.clubId, entityType, request)
        }
        val items = result.content.map {
            AuditEntry(it.id, it.createdAt, it.actorId, it.action, it.entityType, it.entityId, it.summary,
                it.beforeState, it.afterState)
        }
        return AuditPage(items, result.number, result.size, result.totalElements)
    }
}
