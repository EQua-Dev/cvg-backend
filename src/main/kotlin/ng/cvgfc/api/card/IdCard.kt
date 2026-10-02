package ng.cvgfc.api.card

import ng.cvgfc.api.auth.CurrentMember
import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.common.Hashing
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.Member
import ng.cvgfc.api.member.MemberRepository
import ng.cvgfc.api.member.MemberService
import ng.cvgfc.api.member.MemberStatus
import ng.cvgfc.api.member.Role
import ng.cvgfc.api.profile.EmergencyContact
import ng.cvgfc.api.profile.ProfileService
import ng.cvgfc.api.profile.photoResponse
import ng.cvgfc.api.season.SeasonService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Everything printed on a member's CVG ID card. */
data class CardView(
    val memberId: UUID,
    val code: String,
    val fullName: String,
    val nickname: String?,
    val jerseyNumber: Short?,
    val status: MemberStatus,
    val current: Boolean,
    val roles: Set<Role>,
    val position: String?,
    val photoUrl: String?,
    val season: String?,
    val validUntil: LocalDate?,
    val joinedOn: LocalDate,
    /** Full URL encoded in the QR code. */
    val verifyUrl: String,
    /** Back of the card. */
    val emergencyContact: EmergencyContact?,
)

/** What anyone scanning the QR code sees. No phone numbers or private details. */
data class VerifyView(
    val code: String,
    val fullName: String,
    val jerseyNumber: Short?,
    val status: MemberStatus,
    val current: Boolean,
    val season: String?,
    val position: String?,
    val photoUrl: String?,
)

@Service
class IdCardService(
    private val members: MemberService,
    private val memberRepo: MemberRepository,
    private val profiles: ProfileService,
    private val seasons: SeasonService,
    private val properties: CvgProperties,
) {
    init {
        if (properties.links.verifySecret == "dev-only-change-me") {
            LoggerFactory.getLogger(javaClass).warn("CVG_VERIFY_SECRET is not set: ID card QR links use the dev secret.")
        }
    }

    @Transactional(readOnly = true)
    fun card(memberId: UUID): CardView {
        val m = members.get(memberId)
        val profile = profiles.toView(m.id, profiles.find(m.id), includePrivate = true)
        val season = seasons.current()
        return CardView(
            memberId = m.id,
            code = m.code,
            fullName = m.fullName,
            nickname = m.nickname,
            jerseyNumber = m.jerseyNumber,
            status = m.status,
            current = m.status.isCurrent,
            roles = m.roles.toSortedSet(),
            position = profile.favouredPosition?.label,
            photoUrl = profile.photoUrl,
            season = season?.name,
            validUntil = season?.endsOn,
            joinedOn = m.joinedOn,
            verifyUrl = verifyUrl(m),
            emergencyContact = profile.emergencyContact,
        )
    }

    @Transactional(readOnly = true)
    fun verify(code: String, signature: String): VerifyView {
        val m = resolve(code, signature)
        val profile = profiles.find(m.id)
        val public = profile?.showsPublicly == true
        return VerifyView(
            code = m.code,
            fullName = m.fullName,
            jerseyNumber = m.jerseyNumber,
            status = m.status,
            current = m.status.isCurrent,
            season = seasons.current()?.name,
            position = if (public) profile?.favouredPosition?.label else null,
            photoUrl = if (public && profile?.photoId != null) "/api/public/verify/${m.code}/$signature/photo" else null,
        )
    }

    @Transactional(readOnly = true)
    fun publicPhoto(code: String, signature: String) =
        resolve(code, signature).let { m -> if (profiles.find(m.id)?.showsPublicly == true) profiles.photo(m.id) else null }

    fun verifyUrl(m: Member) = "${properties.links.publicSite.trimEnd('/')}/verify/${m.code}/${signature(m.id)}"

    /** Short HMAC of the member id: unguessable, so codes can't be enumerated. */
    fun signature(memberId: UUID): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.links.verifySecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(memberId.toString().toByteArray()).take(6).joinToString("") { "%02x".format(it) }
    }

    private fun resolve(code: String, signature: String): Member {
        val notFound = ApiException(HttpStatus.NOT_FOUND, "card_not_found", "We can't find this card.")
        val number = Regex("(?i)^CVG-?(\\d{1,6})$").find(code.trim())?.groupValues?.get(1)?.toIntOrNull() ?: throw notFound
        val m = memberRepo.findByClubIdAndMemberNo(properties.clubId, number) ?: throw notFound
        if (!Hashing.constantTimeEquals(signature(m.id), signature.lowercase())) throw notFound
        return m
    }
}

@RestController
class IdCardController(private val service: IdCardService) {

    @GetMapping("/api/me/card")
    fun mine(@AuthenticationPrincipal me: CurrentMember) = service.card(me.id)

    @GetMapping("/api/members/{id}/card")
    @PreAuthorize("hasAnyRole('ADMIN','COACH','TREASURER','CAPTAIN')")
    fun ofMember(@PathVariable id: UUID) = service.card(id)

    @GetMapping("/api/public/verify/{code}/{signature}")
    fun verify(@PathVariable code: String, @PathVariable signature: String) = service.verify(code, signature)

    @GetMapping("/api/public/verify/{code}/{signature}/photo")
    fun verifyPhoto(
        @PathVariable code: String,
        @PathVariable signature: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> = photoResponse(service.publicPhoto(code, signature), ifNoneMatch, public = true)
}
