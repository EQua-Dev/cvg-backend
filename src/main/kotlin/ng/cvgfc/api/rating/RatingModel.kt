package ng.cvgfc.api.rating

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
data class StatSetId(
    @Column(name = "club_id") val clubId: UUID = UUID(0, 0),
    @Column(name = "position_group") val group: String = "",
) : Serializable

@Entity
@Table(name = "stat_set")
class StatSet(
    @EmbeddedId val id: StatSetId,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var attrs: List<String>,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

interface StatSetRepository : JpaRepository<StatSet, StatSetId> {
    fun findByIdClubId(clubId: UUID): List<StatSet>
}

@Entity
@Table(name = "rating_window")
class RatingWindow(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(name = "season_id", updatable = false)
    val seasonId: UUID?,
    @Column(nullable = false)
    var title: String,
    @Column(name = "opens_at", nullable = false, updatable = false)
    val opensAt: Instant,
    @Column(name = "closes_at", nullable = false)
    var closesAt: Instant,
    @Column(name = "created_by", updatable = false)
    val createdBy: UUID?,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    @Column(name = "closed_by")
    var closedBy: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    fun isOpen(now: Instant) = closedAt == null && now.isBefore(closesAt)
}

interface RatingWindowRepository : JpaRepository<RatingWindow, UUID> {
    fun findByIdAndClubId(id: UUID, clubId: UUID): RatingWindow?
    fun findByClubIdOrderByOpensAtDesc(clubId: UUID): List<RatingWindow>
    fun findByClubIdAndClosedAtIsNull(clubId: UUID): RatingWindow?
}

@Embeddable
data class RatingId(
    @Column(name = "window_id") val windowId: UUID = UUID(0, 0),
    @Column(name = "rater_id") val raterId: UUID = UUID(0, 0),
    @Column(name = "ratee_id") val rateeId: UUID = UUID(0, 0),
    @Column(name = "attr") val attr: String = "",
) : Serializable

@Entity
@Table(name = "rating")
class Rating(
    @EmbeddedId val id: RatingId,
    /** Null = "don't know". */
    var score: Short?,
    @Column(name = "rated_at", nullable = false)
    var ratedAt: Instant,
)

interface RatingRepository : JpaRepository<Rating, RatingId> {
    fun findByIdWindowId(windowId: UUID): List<Rating>
    fun findByIdWindowIdAndIdRaterId(windowId: UUID, raterId: UUID): List<Rating>
    fun findByIdWindowIdAndIdRaterIdAndIdRateeId(windowId: UUID, raterId: UUID, rateeId: UUID): List<Rating>

    @Query("select r.id.raterId, count(r) from Rating r where r.id.windowId = ?1 group by r.id.raterId")
    fun answeredPerRater(windowId: UUID): List<Array<Any>>
}

@Entity
@Table(name = "player_card")
class PlayerCard(
    @Column(name = "window_id", nullable = false, updatable = false)
    val windowId: UUID,
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: UUID,
    /** Attribute code → card stat 30–99 (only attributes with peer scores). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    val stats: Map<String, Int>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "peer_counts", nullable = false, updatable = false)
    val peerCounts: Map<String, Int>,
    /** The player's own answers on the card scale; only ever shown to them. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "self_stats", nullable = false, updatable = false)
    val selfStats: Map<String, Int>,
    @Column(updatable = false)
    val position: String?,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

interface PlayerCardRepository : JpaRepository<PlayerCard, UUID> {
    fun findByMemberIdOrderByCreatedAtDesc(memberId: UUID): List<PlayerCard>
    fun findByWindowId(windowId: UUID): List<PlayerCard>
    fun findByMemberIdIn(ids: Collection<UUID>): List<PlayerCard>
}
