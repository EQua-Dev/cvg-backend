package ng.cvgfc.api.match

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import ng.cvgfc.api.training.Availability
import ng.cvgfc.api.training.OutReason
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.time.Instant
import java.util.UUID

enum class MatchSide { HOME, AWAY, NEUTRAL }
enum class MatchType { FRIENDLY, TOURNAMENT, LEAGUE, INTERNAL }
enum class MatchStatus { SCHEDULED, PLAYED, CANCELLED }
enum class GoalKind { OPEN_PLAY, PENALTY, FREE_KICK, HEADER }
enum class CardColour { YELLOW, RED }

/** The five game plans, same codes as the profiling questionnaire. */
enum class GamePlan(val label: String) {
    POS("Possession"), CTR("Counter-attack"), PRS("High press"), BLK("Defensive"), DIR("Direct");

    companion object {
        fun parse(code: String?): GamePlan? = entries.firstOrNull { it.name == code }
    }
}

@Entity
@Table(name = "match")
class Match(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(name = "season_id")
    var seasonId: UUID?,
    @Column(nullable = false)
    var opponent: String,
    @Column(name = "kickoff_at", nullable = false)
    var kickoffAt: Instant,
    @Column(nullable = false)
    var venue: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var side: MatchSide,
    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false)
    var type: MatchType,
    @Column(name = "team_size", nullable = false)
    var teamSize: Short,
    @Column(name = "created_by", updatable = false)
    val createdBy: UUID?,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "meet_at")
    var meetAt: Instant? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "game_plan")
    var gamePlan: GamePlan? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "plan_b")
    var planB: GamePlan? = null

    var kit: String? = null

    @Column(name = "fee_kobo")
    var feeKobo: Long? = null

    @Column(name = "fee_collection_id")
    var feeCollectionId: UUID? = null

    var notes: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    var weights: Map<String, Int>? = null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: MatchStatus = MatchStatus.SCHEDULED

    var formation: String? = null

    @Column(name = "captain_id")
    var captainId: UUID? = null

    @Column(name = "penalty_taker_id")
    var penaltyTakerId: UUID? = null

    @Column(name = "free_kick_taker_id")
    var freeKickTakerId: UUID? = null

    @Column(name = "corner_taker_id")
    var cornerTakerId: UUID? = null

    @Column(name = "lineup_published_at")
    var lineupPublishedAt: Instant? = null

    @Column(name = "our_score")
    var ourScore: Short? = null

    @Column(name = "their_score")
    var theirScore: Short? = null

    @Column(name = "result_saved_at")
    var resultSavedAt: Instant? = null

    @Column(name = "potm_closes_at")
    var potmClosesAt: Instant? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

interface MatchRepository : JpaRepository<Match, UUID> {
    fun findByIdAndClubId(id: UUID, clubId: UUID): Match?
    fun findByClubIdOrderByKickoffAtDesc(clubId: UUID): List<Match>
    fun findByClubIdAndStatusOrderByKickoffAtDesc(clubId: UUID, status: MatchStatus): List<Match>
    fun findByClubIdAndKickoffAtAfterOrderByKickoffAt(clubId: UUID, after: Instant): List<Match>
}

@Embeddable
data class MatchMemberId(
    @Column(name = "match_id") val matchId: UUID = UUID(0, 0),
    @Column(name = "member_id") val memberId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "match_availability")
class MatchAvailability(
    @EmbeddedId val id: MatchMemberId,
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

interface MatchAvailabilityRepository : JpaRepository<MatchAvailability, MatchMemberId> {
    fun findByIdMatchId(matchId: UUID): List<MatchAvailability>
    fun findByIdMatchIdIn(ids: Collection<UUID>): List<MatchAvailability>
}

@Embeddable
data class LineupSlotId(
    @Column(name = "match_id") val matchId: UUID = UUID(0, 0),
    @Column(name = "idx") val idx: Short = 0,
) : Serializable

@Entity
@Table(name = "lineup_slot")
class LineupSlot(
    @EmbeddedId val id: LineupSlotId,
    @Column(name = "member_id")
    val memberId: UUID?,
    @Column(name = "guest_name")
    val guestName: String?,
) {
    val idx: Int get() = id.idx.toInt()
    val bench: Boolean get() = idx >= BENCH_START
}

const val BENCH_START = 100

interface LineupSlotRepository : JpaRepository<LineupSlot, LineupSlotId> {
    fun findByIdMatchId(matchId: UUID): List<LineupSlot>
    fun findByIdMatchIdIn(ids: Collection<UUID>): List<LineupSlot>
    fun deleteByIdMatchId(matchId: UUID)
}

@Entity
@Table(name = "match_appearance")
class MatchAppearance(
    @Column(name = "match_id", nullable = false, updatable = false)
    val matchId: UUID,
    @Column(name = "member_id", updatable = false)
    val memberId: UUID?,
    @Column(name = "guest_name", updatable = false)
    val guestName: String?,
    @Column(nullable = false, updatable = false)
    val started: Boolean,
    @Column(updatable = false)
    val position: String?,
) {
    @Id
    val id: UUID = UUID.randomUUID()
}

interface MatchAppearanceRepository : JpaRepository<MatchAppearance, UUID> {
    fun findByMatchId(matchId: UUID): List<MatchAppearance>
    fun findByMatchIdIn(ids: Collection<UUID>): List<MatchAppearance>
    fun findByMemberId(memberId: UUID): List<MatchAppearance>
    fun deleteByMatchId(matchId: UUID)
}

@Entity
@Table(name = "match_goal")
class MatchGoal(
    @Column(name = "match_id", nullable = false, updatable = false)
    val matchId: UUID,
    @Column(nullable = false, updatable = false)
    val seq: Short,
    @Column(name = "scorer_member_id", updatable = false)
    val scorerMemberId: UUID?,
    @Column(name = "scorer_guest", updatable = false)
    val scorerGuest: String?,
    @Column(name = "own_goal", nullable = false, updatable = false)
    val ownGoal: Boolean,
    @Column(name = "assist_member_id", updatable = false)
    val assistMemberId: UUID?,
    @Column(name = "assist_guest", updatable = false)
    val assistGuest: String?,
    @Column(updatable = false)
    val minute: Short?,
    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    val kind: GoalKind?,
) {
    @Id
    val id: UUID = UUID.randomUUID()
}

interface MatchGoalRepository : JpaRepository<MatchGoal, UUID> {
    fun findByMatchIdOrderBySeq(matchId: UUID): List<MatchGoal>
    fun findByMatchIdIn(ids: Collection<UUID>): List<MatchGoal>
    fun deleteByMatchId(matchId: UUID)
}

@Entity
@Table(name = "match_card")
class MatchCard(
    @Column(name = "match_id", nullable = false, updatable = false)
    val matchId: UUID,
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val colour: CardColour,
    @Column(updatable = false)
    val minute: Short?,
) {
    @Id
    val id: UUID = UUID.randomUUID()
}

interface MatchCardRepository : JpaRepository<MatchCard, UUID> {
    fun findByMatchId(matchId: UUID): List<MatchCard>
    fun deleteByMatchId(matchId: UUID)
}

@Embeddable
data class MatchVoterId(
    @Column(name = "match_id") val matchId: UUID = UUID(0, 0),
    @Column(name = "voter_id") val voterId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "potm_vote")
class PotmVote(
    @EmbeddedId val id: MatchVoterId,
    @Column(name = "nominee_id", nullable = false)
    var nomineeId: UUID,
    @Column(name = "voted_at", nullable = false)
    var votedAt: Instant,
)

interface PotmVoteRepository : JpaRepository<PotmVote, MatchVoterId> {
    fun findByIdMatchId(matchId: UUID): List<PotmVote>
    fun findByIdMatchIdIn(ids: Collection<UUID>): List<PotmVote>
}

@Embeddable
data class MatchAuthorId(
    @Column(name = "match_id") val matchId: UUID = UUID(0, 0),
    @Column(name = "author_id") val authorId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "match_opinion")
class MatchOpinion(
    @EmbeddedId val id: MatchAuthorId,
) {
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "commend_tags", nullable = false)
    var commendTags: List<String> = emptyList()

    @Column(name = "commend_text")
    var commendText: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "critique_tags", nullable = false)
    var critiqueTags: List<String> = emptyList()

    @Column(name = "critique_text")
    var critiqueText: String? = null

    @Column(name = "self_rating")
    var selfRating: Short? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
}

interface MatchOpinionRepository : JpaRepository<MatchOpinion, MatchAuthorId> {
    fun findByIdMatchId(matchId: UUID): List<MatchOpinion>
}

/** Tags for commendations and critiques: one tap each. */
val OPINION_TAGS = listOf(
    "Pressing", "Shape", "Communication", "Finishing", "Passing", "Set pieces", "Defending crosses",
    "Transitions", "Work rate", "Discipline", "Fitness", "Goalkeeping", "Leadership",
)
