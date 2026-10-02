package ng.cvgfc.api.season

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import ng.cvgfc.api.audit.AuditService
import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.config.CvgProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A period that attendance, stats, collections and rating rounds belong to. */
@Entity
@Table(name = "season")
class Season(
    @Column(name = "club_id", nullable = false, updatable = false)
    val clubId: UUID,
    @Column(nullable = false)
    var name: String,
    @Column(name = "starts_on", nullable = false)
    var startsOn: LocalDate,
    @Column(name = "ends_on", nullable = false)
    var endsOn: LocalDate,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var active: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

interface SeasonRepository : JpaRepository<Season, UUID> {
    fun findByClubIdOrderByStartsOnDesc(clubId: UUID): List<Season>
    fun findByIdAndClubId(id: UUID, clubId: UUID): Season?
    fun findByClubIdAndActiveTrue(clubId: UUID): Season?
}

data class SeasonView(
    val id: UUID,
    val name: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val active: Boolean,
) {
    companion object {
        fun of(s: Season) = SeasonView(s.id, s.name, s.startsOn, s.endsOn, s.active)
    }
}

data class CreateSeasonRequest(
    @field:NotBlank(message = "Enter a name.") @field:Size(max = 60)
    val name: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
    val activate: Boolean = false,
)

@Service
class SeasonService(
    private val seasons: SeasonRepository,
    private val audit: AuditService,
    private val properties: CvgProperties,
) {
    private val clubId get() = properties.clubId

    @Transactional(readOnly = true)
    fun list(): List<Season> = seasons.findByClubIdOrderByStartsOnDesc(clubId)

    @Transactional(readOnly = true)
    fun current(): Season? = seasons.findByClubIdAndActiveTrue(clubId)

    @Transactional
    fun create(req: CreateSeasonRequest, actorId: UUID): Season {
        if (!req.endsOn.isAfter(req.startsOn)) {
            throw ApiException.badRequest("invalid_dates", "End date must be after start date.")
        }
        val season = seasons.save(Season(clubId, req.name.trim(), req.startsOn, req.endsOn))
        audit.record(actorId, "season.created", "season", season.id, "Season ${season.name}")
        if (req.activate) activate(season.id, actorId)
        return season
    }

    /** Makes this the one active season; the previous active season is closed. */
    @Transactional
    fun activate(id: UUID, actorId: UUID): Season {
        val target = seasons.findByIdAndClubId(id, clubId) ?: throw ApiException.notFound("Season")
        if (target.active) return target
        seasons.findByClubIdAndActiveTrue(clubId)?.let {
            it.active = false
            seasons.saveAndFlush(it)
        }
        target.active = true
        audit.record(actorId, "season.activated", "season", id, "Active season: ${target.name}")
        return target
    }
}

@RestController
@RequestMapping("/api/seasons")
class SeasonController(private val service: SeasonService) {

    @GetMapping
    fun list() = service.list().map(SeasonView::of)

    @GetMapping("/current")
    fun current() = service.current()?.let(SeasonView::of) ?: throw ApiException.notFound("Active season")

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    fun create(@Valid @RequestBody req: CreateSeasonRequest, @AuthenticationPrincipal me: CurrentMember) =
        SeasonView.of(service.create(req, me.id))

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    fun activate(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) =
        SeasonView.of(service.activate(id, me.id))
}
