package ng.cvgfc.api.member

import jakarta.validation.Valid
import ng.cvgfc.api.auth.CurrentMember
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/members")
class MemberController(private val service: MemberService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: MemberStatus?,
        @RequestParam(required = false) role: Role?,
        @RequestParam(required = false) q: String?,
        @AuthenticationPrincipal me: CurrentMember,
    ): List<MemberView> = service.list(status, role, q).map { MemberView.of(it, me.canSeePhoneOf(it)) }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember): MemberView {
        val m = service.get(id)
        return MemberView.of(m, me.canSeePhoneOf(m))
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    fun create(@Valid @RequestBody req: CreateMemberRequest, @AuthenticationPrincipal me: CurrentMember) =
        MemberView.of(service.create(req, me.id), true)

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody req: UpdateMemberRequest,
        @AuthenticationPrincipal me: CurrentMember,
    ) = MemberView.of(service.update(id, req, me.id), true)

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    fun status(@PathVariable id: UUID, @RequestBody req: StatusRequest, @AuthenticationPrincipal me: CurrentMember) =
        MemberView.of(service.changeStatus(id, req.status, me.id), true)

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasRole('ADMIN')")
    fun roles(@PathVariable id: UUID, @RequestBody req: RolesRequest, @AuthenticationPrincipal me: CurrentMember) =
        MemberView.of(service.changeRoles(id, req.roles, me.id), true)

    /** Forgotten passcode: back to the last 4 digits of their phone, and all devices signed out. */
    @PostMapping("/{id}/reset-passcode")
    @PreAuthorize("hasRole('ADMIN')")
    fun resetPasscode(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember): ResponseEntity<Void> {
        service.resetPasscode(id, me.id)
        return ResponseEntity.noContent().build()
    }

    private fun CurrentMember.canSeePhoneOf(m: Member) = isManagement || id == m.id
}
