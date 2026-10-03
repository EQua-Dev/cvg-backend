package ng.cvgfc.api.style

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
import java.time.Instant
import java.util.UUID

@Embeddable
data class RoleVoteId(
    @Column(name = "window_id") val windowId: UUID = UUID(0, 0),
    @Column(name = "rater_id") val raterId: UUID = UUID(0, 0),
    @Column(name = "ratee_id") val rateeId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "role_vote")
class RoleVote(
    @EmbeddedId val id: RoleVoteId,
    @Column(name = "role_code", nullable = false)
    var roleCode: String,
    @Column(name = "voted_at", nullable = false)
    var votedAt: Instant,
)

interface RoleVoteRepository : JpaRepository<RoleVote, RoleVoteId> {
    fun findByIdWindowIdAndIdRateeIdIn(windowId: UUID, rateeIds: Collection<UUID>): List<RoleVote>
    fun findByIdWindowIdAndIdRaterId(windowId: UUID, raterId: UUID): List<RoleVote>
}

@Entity
@Table(name = "player_role")
class PlayerRole(
    @Id
    @Column(name = "member_id")
    val memberId: UUID,
    @Column(name = "role_code", nullable = false)
    var roleCode: String,
    @Column(name = "set_by")
    var setBy: UUID?,
    @Column(name = "set_at", nullable = false)
    var setAt: Instant,
)

interface PlayerRoleRepository : JpaRepository<PlayerRole, UUID>

enum class Link { GREEN, AMBER, RED }
enum class RuleScope { NEIGHBOURS, TEAM }

@Entity
@Table(name = "chemistry_rule")
class ChemistryRule(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(name = "role_a", nullable = false)
    val roleA: String,
    @Column(name = "role_b", nullable = false)
    val roleB: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val link: Link,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val scope: RuleScope,
    val plan: String?,
    @Column(name = "unless_role")
    val unlessRole: String?,
    val note: String?,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

interface ChemistryRuleRepository : JpaRepository<ChemistryRule, UUID> {
    fun findByClubIdOrderByCreatedAt(clubId: UUID): List<ChemistryRule>
    fun findByIdAndClubId(id: UUID, clubId: UUID): ChemistryRule?
}

@Entity
@Table(name = "profiling_round")
class ProfilingRound(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(name = "opened_at", nullable = false, updatable = false)
    val openedAt: Instant,
    @Column(name = "opened_by", updatable = false)
    val openedBy: UUID?,
) {
    @Id
    val id: UUID = UUID.randomUUID()
}

interface ProfilingRoundRepository : JpaRepository<ProfilingRound, UUID> {
    fun findFirstByClubIdOrderByOpenedAtDesc(clubId: UUID): ProfilingRound?
}
