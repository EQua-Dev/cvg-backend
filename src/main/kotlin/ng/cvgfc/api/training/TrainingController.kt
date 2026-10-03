package ng.cvgfc.api.training

import ng.cvgfc.api.auth.CurrentMember
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/training")
class TrainingController(private val service: TrainingService, private val stats: AttendanceStats) {

    // --- schedule (coach, admin) ---

    @GetMapping("/patterns")
    fun patterns() = service.patterns()

    @PostMapping("/patterns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun addPattern(@RequestBody req: PatternRequest, @AuthenticationPrincipal me: CurrentMember) = service.addPattern(req, me.id)

    @DeleteMapping("/patterns/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun removePattern(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.removePattern(id, me.id)

    // --- sessions ---

    @GetMapping("/sessions")
    fun sessions(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @AuthenticationPrincipal me: CurrentMember,
    ) = service.list(from, to, me)

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun impromptu(@RequestBody req: ImpromptuRequest, @AuthenticationPrincipal me: CurrentMember) = service.addImpromptu(req, me.id)

    @GetMapping("/sessions/{id}")
    fun detail(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.detail(id, me)

    @PutMapping("/sessions/{id}/focus")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun focus(@PathVariable id: UUID, @RequestBody req: FocusRequest, @AuthenticationPrincipal me: CurrentMember) =
        service.setFocus(id, req.focus, me.id)

    @PostMapping("/sessions/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','COACH')")
    fun cancel(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.cancel(id, me.id)

    // --- availability ---

    @PutMapping("/sessions/{id}/availability")
    fun myAvailability(@PathVariable id: UUID, @RequestBody req: AvailabilityRequest, @AuthenticationPrincipal me: CurrentMember) =
        service.setMyAvailability(id, req, me)

    @PutMapping("/sessions/{id}/availability/{memberId}")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun availabilityFor(
        @PathVariable id: UUID,
        @PathVariable memberId: UUID,
        @RequestBody req: AvailabilityRequest,
        @AuthenticationPrincipal me: CurrentMember,
    ) = service.setAvailabilityFor(id, memberId, req, me.id)

    // --- attendance (coach, captain, admin) ---

    @PutMapping("/sessions/{id}/marks")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun marks(@PathVariable id: UUID, @RequestBody req: MarksRequest, @AuthenticationPrincipal me: CurrentMember) =
        service.mark(id, req, me)

    @PostMapping("/sessions/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun close(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) = service.close(id, me.id)

    // --- stats ---

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','CAPTAIN')")
    fun squadStats() = stats.squad()

    @GetMapping("/me")
    fun mine(@AuthenticationPrincipal me: CurrentMember) = stats.forMember(me.id)
}

/** Keeps the next two weeks of sessions created from the weekly schedule. */
@Component
class TrainingScheduleJob(private val service: TrainingService) {
    @Scheduled(cron = "0 20 0 * * *", zone = "Africa/Lagos")
    fun run() {
        service.generateUpcoming()
    }
}
