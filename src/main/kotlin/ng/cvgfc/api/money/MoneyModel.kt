package ng.cvgfc.api.money

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class Direction { IN, OUT }

/** What money was for. Collections use the "owed" ones; expenses the rest. */
enum class Category(val label: String, val collectable: Boolean = false, val expense: Boolean = false) {
    DUES("Monthly dues", collectable = true),
    CDC("Club Development Contribution", collectable = true),
    TOURNAMENT_FEE("Tournament fee", collectable = true, expense = true),
    KIT("Kit / jersey", collectable = true, expense = true),
    WELFARE("Welfare", collectable = true, expense = true),
    DONATION("Donation"),
    FIELD_RENTAL("Field rental", expense = true),
    EQUIPMENT("Equipment", expense = true),
    TRANSPORT("Transport", expense = true),
    MATCH_FEE("Match / referee fee", expense = true),
    OTHER("Other", collectable = true, expense = true),
}

enum class PaymentMethod { CASH, TRANSFER, POS }

/** Who a new collection is for. The list is fixed at creation; admins can adjust it after. */
enum class Audience { ACTIVE, ACTIVE_AND_TRIALISTS, SELECTED }

@Entity
@Table(name = "collection")
class Collection(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(name = "season_id", updatable = false)
    val seasonId: UUID?,
    @Column(nullable = false)
    var title: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val type: Category,
    @Column(name = "amount_kobo", nullable = false, updatable = false)
    val amountKobo: Long,
    @Column(name = "due_date", nullable = false)
    var dueDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    val audience: Audience,
    @Column(nullable = false, updatable = false)
    val recurring: Boolean,
    @Column(name = "series_id", updatable = false)
    val seriesId: UUID?,
    @Column(updatable = false)
    val period: String?,
    @Column(name = "created_by", updatable = false)
    val createdBy: UUID?,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    val isOpen get() = closedAt == null
}

interface CollectionRepository : JpaRepository<Collection, UUID> {
    fun findByIdAndClubId(id: UUID, clubId: UUID): Collection?
    fun findByClubIdOrderByDueDateDescCreatedAtDesc(clubId: UUID): List<Collection>
    fun findByClubIdAndRecurringTrue(clubId: UUID): List<Collection>
    fun existsBySeriesIdAndPeriod(seriesId: UUID, period: String): Boolean
}

@Embeddable
data class CollectionMemberId(
    @Column(name = "collection_id") val collectionId: UUID = UUID(0, 0),
    @Column(name = "member_id") val memberId: UUID = UUID(0, 0),
) : Serializable

@Entity
@Table(name = "collection_member")
class CollectionMember(@EmbeddedId val id: CollectionMemberId)

interface CollectionMemberRepository : JpaRepository<CollectionMember, CollectionMemberId> {
    @Query("select cm.id.memberId from CollectionMember cm where cm.id.collectionId = :collectionId")
    fun memberIds(collectionId: UUID): List<UUID>

    @Query("select cm.id.collectionId from CollectionMember cm where cm.id.memberId = :memberId")
    fun collectionIdsFor(memberId: UUID): List<UUID>

    @Query("select cm.id.collectionId, count(cm) from CollectionMember cm where cm.id.collectionId in :ids group by cm.id.collectionId")
    fun countsFor(ids: kotlin.collections.Collection<UUID>): List<Array<Any>>
}

/** One movement of money. Never changed or deleted: the database enforces it. */
@Entity
@Immutable
@Table(name = "ledger_entry")
class LedgerEntry(
    @Column(name = "club_id", nullable = false)
    val clubId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val direction: Direction,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val category: Category,
    @Column(name = "amount_kobo", nullable = false)
    val amountKobo: Long,
    @Column(name = "member_id")
    val memberId: UUID?,
    @Column(name = "collection_id")
    val collectionId: UUID?,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val method: PaymentMethod,
    @Column(name = "occurred_on", nullable = false)
    val occurredOn: LocalDate,
    val note: String?,
    @Column(name = "reverses_id")
    val reversesId: UUID?,
    @Column(name = "recorded_by")
    val recordedBy: UUID?,
    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    /** + for money in, − for money out. */
    val signedKobo get() = if (direction == Direction.IN) amountKobo else -amountKobo
}

interface LedgerEntryRepository : JpaRepository<LedgerEntry, UUID> {
    fun findByIdAndClubId(id: UUID, clubId: UUID): LedgerEntry?
    fun findByClubIdAndOccurredOnBetweenOrderByOccurredOnDescRecordedAtDesc(clubId: UUID, from: LocalDate, to: LocalDate): List<LedgerEntry>
    fun findByCollectionId(collectionId: UUID): List<LedgerEntry>
    fun findByCollectionIdIn(ids: kotlin.collections.Collection<UUID>): List<LedgerEntry>
    fun findByMemberIdOrderByOccurredOnDescRecordedAtDesc(memberId: UUID): List<LedgerEntry>
    fun existsByReversesId(reversesId: UUID): Boolean
    fun findByReversesIdIn(ids: kotlin.collections.Collection<UUID>): List<LedgerEntry>

    @Query("select coalesce(sum(case when e.direction = 'IN' then e.amountKobo else -e.amountKobo end), 0) from LedgerEntry e where e.clubId = :clubId")
    fun balance(clubId: UUID): Long
}

@Entity
@Immutable
@Table(name = "ledger_receipt")
class LedgerReceipt(
    @Id
    @Column(name = "entry_id")
    val entryId: UUID,
    @Column(name = "media_id", nullable = false)
    val mediaId: UUID,
    @Column(name = "added_by")
    val addedBy: UUID?,
    @Column(name = "added_at", nullable = false)
    val addedAt: Instant,
)

interface LedgerReceiptRepository : JpaRepository<LedgerReceipt, UUID> {
    fun findByEntryIdIn(ids: kotlin.collections.Collection<UUID>): List<LedgerReceipt>
}
