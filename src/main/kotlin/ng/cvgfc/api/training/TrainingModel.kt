package ng.cvgfc.api.training

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

enum class SessionKind { COMPULSORY, OPTIONAL }
enum class SessionStatus { SCHEDULED, CLOSED, CANCELLED }
enum class Availability { IN, OUT }
enum class OutReason { INJURED, SICK, TRAVELLING, WORK, FAMILY, OTHER }
enum class Mark {
    PRESENT, LATE, ABSENT, EXCUSED;

    val attended get() = this == PRESENT || this == LATE
}

@Entity
@Table(name = "training_pattern")
class TrainingPattern(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(nullable = false)
    val weekday: Short,
    @Column(name = "start_time", nullable = false)
    val startTime: LocalTime,
    @Column(nullable = false)
    val venue: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: SessionKind,
    @Column(name = "created_by", updatable = false)
    val createdBy: UUID?,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var active: Boolean = true

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    val dayOfWeek: DayOfWeek get() = DayOfWeek.of(weekday.toInt())
}

interface TrainingPatternRepository : JpaRepository<TrainingPattern, UUID> {
    fun findByClubIdAndActiveTrueOrderByWeekdayAscStartTimeAsc(clubId: UUID): List<TrainingPattern>
    fun findByIdAndClubId(id: UUID, clubId: UUID): TrainingPattern?
}

@Entity
@Table(name = "training_session")
class TrainingSession(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(name = "season_id", updatable = false)
    val seasonId: UUID?,
    @Column(name = "pattern_id", updatable = false)
    val patternId: UUID?,
    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant,
    @Column(nullable = false)
    var venue: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: SessionKind,
    @Column(nullable = false, updatable = false)
    val impromptu: Boolean,
    @Column(name = "created_by", updatable = false)
    val createdBy: UUID?,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    var focus: String? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SessionStatus = SessionStatus.SCHEDULED

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    @Column(name = "closed_by")
    var closedBy: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

interface TrainingSessionRepository : JpaRepository<TrainingSession, UUID> {
    fun findByIdAndClubId(id: UUID, clubId: UUID): TrainingSession?
    fun findByClubIdAndStartsAtBetweenOrderByStartsAt(clubId: UUID, from: Instant, to: Instant): List<TrainingSession>
    fun findByClubIdAndStatusOrderByStartsAt(clubId: UUID, status: SessionStatus): List<TrainingSession>
    fun findByPatternIdAndStartsAtAfter(patternId: UUID, after: Instant): List<TrainingSession>
    fun existsByPatternIdAndStartsAt(patternId: UUID, startsAt: Instant): Boolean
}

@Embeddable
data class SessionMemberId(
    @Column(name = "session_id") val sessionId: UUID = UUID(0, 0),
    @Column(name = "member_id") val memberId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "session_availability")
class SessionAvailability(
    @EmbeddedId val id: SessionMemberId,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: Availability,
    @Enumerated(EnumType.STRING)
    var reason: OutReason?,
    @Column(name = "set_by")
    var setBy: UUID?,
    @Column(name = "set_at", nullable = false)
    var setAt: Instant,
)

interface SessionAvailabilityRepository : JpaRepository<SessionAvailability, SessionMemberId> {
    fun findByIdSessionId(sessionId: UUID): List<SessionAvailability>
    fun findByIdSessionIdIn(sessionIds: Collection<UUID>): List<SessionAvailability>
}

@Entity
@Table(name = "attendance_mark")
class AttendanceMark(
    @EmbeddedId val id: SessionMemberId,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var mark: Mark,
    @Column(name = "marked_by")
    var markedBy: UUID?,
    @Column(name = "client_marked_at", nullable = false)
    var clientMarkedAt: Instant,
    @Column(name = "synced_at", nullable = false)
    var syncedAt: Instant,
)

interface AttendanceMarkRepository : JpaRepository<AttendanceMark, SessionMemberId> {
    fun findByIdSessionId(sessionId: UUID): List<AttendanceMark>
    fun findByIdSessionIdIn(sessionIds: Collection<UUID>): List<AttendanceMark>
}
