package ng.cvgfc.api.member

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Management roles. Every member is implicitly a player; these are extra hats. */
enum class Role {
    /** Everything, including roles, seasons and the audit log. */
    ADMIN,

    /** Training, attendance, matches, selection, rating windows. */
    COACH,

    /** Payments and collections. */
    TREASURER,

    /** Attendance and availability, helps with selection. No money. */
    CAPTAIN,
}

enum class MemberStatus {
    TRIALIST, ACTIVE, INACTIVE, LEFT;

    /** Counted in selection, dues, attendance and voting. */
    val isCurrent get() = this == TRIALIST || this == ACTIVE

    /** Members who have left keep their history but lose access. */
    val canSignIn get() = this != LEFT

    companion object {
        val CURRENT = setOf(TRIALIST, ACTIVE)
    }
}

@Entity
@Table(name = "member")
class Member(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,

    @Column(name = "member_no", nullable = false, updatable = false)
    val memberNo: Int,

    @Column(name = "full_name", nullable = false)
    var fullName: String,

    @Column(nullable = false)
    var phone: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MemberStatus,

    @Column(name = "joined_on", nullable = false)
    var joinedOn: LocalDate,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    var nickname: String? = null

    @Column(name = "jersey_number")
    var jerseyNumber: Short? = null

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "member_role", joinColumns = [JoinColumn(name = "member_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    var roles: MutableSet<Role> = mutableSetOf()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
        protected set

    @Version
    var version: Long = 0
        protected set

    /** Public member code, e.g. CVG-0009. Never reused. */
    val code get() = "CVG-%04d".format(memberNo)

    val isManagement get() = roles.isNotEmpty()

    fun hasRole(role: Role) = role in roles

    @PrePersist
    fun onCreate() {
        createdAt = Instant.now()
        updatedAt = createdAt
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    fun snapshot(): Map<String, Any?> = linkedMapOf(
        "fullName" to fullName,
        "nickname" to nickname,
        "phone" to phone,
        "jerseyNumber" to jerseyNumber,
        "status" to status.name,
        "joinedOn" to joinedOn.toString(),
        "roles" to roles.map { it.name }.sorted(),
    )
}
