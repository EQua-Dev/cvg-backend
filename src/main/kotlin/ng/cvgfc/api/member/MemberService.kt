package ng.cvgfc.api.member

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import ng.cvgfc.api.audit.AuditService
import ng.cvgfc.api.auth.AuthService
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.common.PhoneNumbers
import ng.cvgfc.api.config.CvgProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** A member as seen in the app. [phone] is only filled for management and for the member themself. */
data class MemberView(
    val id: UUID,
    val code: String,
    val fullName: String,
    val nickname: String?,
    val jerseyNumber: Short?,
    val status: MemberStatus,
    val joinedOn: LocalDate,
    val roles: Set<Role>,
    val phone: String?,
) {
    companion object {
        fun of(m: Member, includePhone: Boolean) = MemberView(
            m.id, m.code, m.fullName, m.nickname, m.jerseyNumber, m.status, m.joinedOn, m.roles.toSortedSet(),
            if (includePhone) PhoneNumbers.display(m.phone) else null,
        )
    }
}

data class CreateMemberRequest(
    @field:NotBlank(message = "Enter a full name.") @field:Size(max = 120)
    val fullName: String,
    @field:Size(max = 40)
    val nickname: String? = null,
    @field:NotBlank(message = "Enter a phone number.")
    val phone: String,
    @field:Min(1, message = "1–99 only.") @field:Max(99, message = "1–99 only.")
    val jerseyNumber: Short? = null,
    val status: MemberStatus? = null,
    val joinedOn: LocalDate? = null,
    val roles: Set<Role>? = null,
)

/** Full replace of the admin-managed profile fields. Null nickname/jersey clears them. */
data class UpdateMemberRequest(
    @field:NotBlank(message = "Enter a full name.") @field:Size(max = 120)
    val fullName: String,
    @field:Size(max = 40)
    val nickname: String? = null,
    @field:NotBlank(message = "Enter a phone number.")
    val phone: String,
    @field:Min(1, message = "1–99 only.") @field:Max(99, message = "1–99 only.")
    val jerseyNumber: Short? = null,
    val joinedOn: LocalDate,
)

data class StatusRequest(val status: MemberStatus)

data class RolesRequest(val roles: Set<Role>)

@Service
class MemberService(
    private val members: MemberRepository,
    private val audit: AuditService,
    private val properties: CvgProperties,
    private val clock: Clock,
    private val authService: AuthService,
) {
    private val clubId get() = properties.clubId

    @Transactional(readOnly = true)
    fun list(status: MemberStatus? = null, role: Role? = null, query: String? = null): List<Member> {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return members.findByClubIdOrderByMemberNo(clubId)
            .filter { status == null || it.status == status }
            .filter { role == null || it.hasRole(role) }
            .filter { q == null || it.matches(q) }
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): Member = members.findByIdAndClubId(id, clubId) ?: throw ApiException.notFound("Member")

    @Transactional
    fun create(req: CreateMemberRequest, actorId: UUID?): Member {
        val phone = PhoneNumbers.require(req.phone)
        val status = req.status ?: MemberStatus.TRIALIST
        if (members.findByClubIdAndPhone(clubId, phone) != null) {
            throw ApiException.conflict("phone_taken", "That phone is already on the register.")
        }
        checkJersey(req.jerseyNumber, status, null)

        val m = Member(
            clubId = clubId,
            memberNo = members.maxMemberNo(clubId) + 1,
            fullName = req.fullName.trim(),
            phone = phone,
            status = status,
            joinedOn = req.joinedOn ?: LocalDate.now(clock),
        ).apply {
            nickname = req.nickname.blankToNull()
            jerseyNumber = req.jerseyNumber
            roles = req.roles.orEmpty().toMutableSet()
        }
        members.save(m)
        audit.record(actorId, "member.created", "member", m.id, "Added ${m.fullName}", after = m.snapshot())
        return m
    }

    @Transactional
    fun update(id: UUID, req: UpdateMemberRequest, actorId: UUID): Member {
        val m = get(id)
        val before = m.snapshot()
        val phone = PhoneNumbers.require(req.phone)
        if (members.existsByClubIdAndPhoneAndIdNot(clubId, phone, id)) {
            throw ApiException.conflict("phone_taken", "That phone is already on the register.")
        }
        checkJersey(req.jerseyNumber, m.status, id)

        m.fullName = req.fullName.trim()
        m.nickname = req.nickname.blankToNull()
        m.phone = phone
        m.jerseyNumber = req.jerseyNumber
        m.joinedOn = req.joinedOn
        audit.record(actorId, "member.updated", "member", id, "Edited ${m.fullName}", before, m.snapshot())
        return m
    }

    @Transactional
    fun changeStatus(id: UUID, status: MemberStatus, actorId: UUID): Member {
        val m = get(id)
        if (m.status == status) return m
        checkJersey(m.jerseyNumber, status, id)
        if (m.hasRole(Role.ADMIN) && !status.canSignIn) ensureAnotherAdmin(id)

        val before = m.snapshot()
        m.status = status
        audit.record(actorId, "member.status_changed", "member", id,
            "${m.fullName}: ${before["status"]} → $status", before, m.snapshot())
        return m
    }

    @Transactional
    fun changeRoles(id: UUID, roles: Set<Role>, actorId: UUID): Member {
        val m = get(id)
        if (m.hasRole(Role.ADMIN) && Role.ADMIN !in roles) ensureAnotherAdmin(id)

        val before = m.snapshot()
        m.roles.clear()
        m.roles.addAll(roles)
        audit.record(actorId, "member.roles_changed", "member", id, "Roles for ${m.fullName}", before, m.snapshot())
        return m
    }

    @Transactional
    fun resetPasscode(id: UUID, actorId: UUID) {
        authService.resetPasscode(get(id), actorId)
    }

    private fun ensureAnotherAdmin(excluding: UUID) {
        val another = members.findByClubIdOrderByMemberNo(clubId)
            .any { it.id != excluding && it.hasRole(Role.ADMIN) && it.status.canSignIn }
        if (!another) throw ApiException.conflict("last_admin", "The club needs at least one admin.")
    }

    /** Jersey numbers are unique among current (trialist/active) members only. */
    private fun checkJersey(jersey: Short?, status: MemberStatus, memberId: UUID?) {
        if (jersey == null || !status.isCurrent) return
        val taken = members.existsByClubIdAndJerseyNumberAndStatusInAndIdNot(
            clubId, jersey, MemberStatus.CURRENT, memberId ?: UUID(0, 0),
        )
        if (taken) throw ApiException.conflict("jersey_taken", "Jersey #$jersey is taken.")
    }

    private fun Member.matches(q: String) =
        fullName.lowercase().contains(q) ||
            nickname?.lowercase()?.contains(q) == true ||
            jerseyNumber?.toString() == q ||
            code.lowercase() == q

    private fun String?.blankToNull() = this?.trim()?.takeIf { it.isNotEmpty() }
}
