package ng.cvgfc.api.profile

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "media_asset")
class MediaAsset(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(name = "content_type", nullable = false, updatable = false)
    val contentType: String,
    @Column(name = "size_bytes", nullable = false, updatable = false)
    val sizeBytes: Int,
    @Column(nullable = false, updatable = false)
    val data: ByteArray,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

interface MediaAssetRepository : JpaRepository<MediaAsset, UUID>

/** Everything a player fills in about themself. One row per member, created on first save. */
@Entity
@Table(name = "member_profile")
class MemberProfile(
    @Id
    @Column(name = "member_id")
    val memberId: UUID,
) {
    @Column(name = "photo_id")
    var photoId: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "favoured_position")
    var favouredPosition: Position? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "other_positions", nullable = false)
    var otherPositions: List<String> = emptyList()

    @Enumerated(EnumType.STRING)
    @Column(name = "dominant_foot")
    var dominantFoot: Foot? = null

    @Column(name = "weak_foot")
    var weakFoot: Short? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var strengths: List<String> = emptyList()

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var weaknesses: List<String> = emptyList()

    @Column(name = "height_cm")
    var heightCm: Short? = null

    @Column(name = "date_of_birth")
    var dateOfBirth: LocalDate? = null

    @Column(name = "state_of_origin")
    var stateOfOrigin: String? = null

    @Column(name = "preferred_jersey")
    var preferredJersey: Short? = null

    @Column(name = "emergency_name")
    var emergencyName: String? = null

    @Column(name = "emergency_phone")
    var emergencyPhone: String? = null

    @Column(name = "consent_public")
    var consentPublic: Boolean? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @Version
    var version: Long = 0
        protected set

    /** What still needs filling in, in wizard order. Empty means the profile is complete. */
    fun missing(): List<String> = buildList {
        if (photoId == null) add("photo")
        if (favouredPosition == null) add("favouredPosition")
        if (dominantFoot == null) add("dominantFoot")
        if (emergencyName.isNullOrBlank() || emergencyPhone.isNullOrBlank()) add("emergencyContact")
        if (consentPublic == null) add("consent")
    }

    /** Photos and position can be shown publicly only with consent. */
    val showsPublicly get() = consentPublic == true
}

interface MemberProfileRepository : JpaRepository<MemberProfile, UUID>
