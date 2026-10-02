package ng.cvgfc.api.profiling

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.member.MemberService
import ng.cvgfc.api.profile.PositionGroup
import ng.cvgfc.api.profile.ProfileService
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "profiling_response")
class ProfilingResponse(
    @Column(name = "member_id", nullable = false, updatable = false)
    val memberId: UUID,
    @Column(name = "questionnaire_version", nullable = false, updatable = false)
    val questionnaireVersion: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "position_group", nullable = false, updatable = false)
    val positionGroup: PositionGroup,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    val answers: Map<String, String>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    val result: ProfilingResult,
    @Column(name = "submitted_at", nullable = false, updatable = false)
    val submittedAt: Instant,
) {
    @Id
    val id: UUID = UUID.randomUUID()
}

interface ProfilingResponseRepository : JpaRepository<ProfilingResponse, UUID> {
    fun findFirstByMemberIdOrderBySubmittedAtDesc(memberId: UUID): ProfilingResponse?
}

// --- API shapes: questions without their points ---

data class OptionView(val id: String, val text: String)
data class QuestionView(val id: String, val text: String, val options: List<OptionView>, val scored: Boolean)
data class QuestionnaireView(
    val version: Int,
    val title: String,
    val subtitle: String,
    val group: PositionGroup,
    val questions: List<QuestionView>,
)

data class SubmitRequest(val version: Int, val answers: Map<String, String>)

data class LatestResult(val result: ProfilingResult, val submittedAt: Instant)

@Service
class ProfilingService(
    private val questionnaire: Questionnaire,
    private val responses: ProfilingResponseRepository,
    private val profiles: ProfileService,
    private val members: MemberService,
    private val clock: Clock,
) {
    private fun positionOf(memberId: UUID) = profiles.find(memberId)?.favouredPosition
        ?: throw ApiException.conflict("no_position", "Pick your main position first.")

    @Transactional(readOnly = true)
    fun questionnaireFor(memberId: UUID): QuestionnaireView {
        val position = positionOf(memberId)
        val def = questionnaire.def
        return QuestionnaireView(
            version = def.version,
            title = def.intro.title,
            subtitle = def.intro.subtitle,
            group = position.group,
            questions = questionnaire.questionsFor(position).map { q ->
                QuestionView(q.id, q.text, q.options.map { OptionView(it.id, it.text) }, q.scored)
            },
        )
    }

    @Transactional
    fun submit(memberId: UUID, req: SubmitRequest): ProfilingResult {
        if (req.version != questionnaire.def.version) {
            throw ApiException.conflict("questionnaire_changed", "The questions changed. Please start again.")
        }
        val position = positionOf(memberId)
        val result = try {
            questionnaire.score(position, req.answers)
        } catch (e: IllegalArgumentException) {
            throw ApiException.badRequest("incomplete", e.message ?: "Answer every question.")
        }
        responses.save(ProfilingResponse(memberId, req.version, position.group, req.answers, result, Instant.now(clock)))
        return result
    }

    @Transactional(readOnly = true)
    fun latest(memberId: UUID): LatestResult? {
        members.get(memberId)
        return responses.findFirstByMemberIdOrderBySubmittedAtDesc(memberId)?.let { LatestResult(it.result, it.submittedAt) }
    }
}

@RestController
class ProfilingController(private val service: ProfilingService) {

    @GetMapping("/api/me/profiling/questionnaire")
    fun questionnaire(@AuthenticationPrincipal me: CurrentMember) = service.questionnaireFor(me.id)

    @PostMapping("/api/me/profiling")
    fun submit(@RequestBody req: SubmitRequest, @AuthenticationPrincipal me: CurrentMember) = service.submit(me.id, req)

    @GetMapping("/api/me/profiling")
    fun mine(@AuthenticationPrincipal me: CurrentMember): ResponseEntity<LatestResult> = latestOrNoContent(me.id)

    /** The label is shown to the squad; the full breakdown is for the member and management. */
    @GetMapping("/api/members/{id}/profiling")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun ofMember(@PathVariable id: UUID): ResponseEntity<LatestResult> = latestOrNoContent(id)

    private fun latestOrNoContent(id: UUID): ResponseEntity<LatestResult> =
        service.latest(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.status(HttpStatus.NO_CONTENT).build()
}
