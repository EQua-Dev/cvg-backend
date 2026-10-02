package ng.cvgfc.api.profile

import ng.cvgfc.api.common.ApiException
import ng.cvgfc.api.common.PhoneNumbers
import ng.cvgfc.api.config.CvgProperties
import ng.cvgfc.api.member.MemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.util.UUID

data class EmergencyContact(val name: String, val phone: String)

data class ProfileView(
    val memberId: UUID,
    val photoUrl: String?,
    val favouredPosition: Position?,
    val positionGroup: PositionGroup?,
    val otherPositions: List<String>,
    val dominantFoot: Foot?,
    val weakFoot: Short?,
    val strengths: List<String>,
    val weaknesses: List<String>,
    val heightCm: Short?,
    val age: Int?,
    val stateOfOrigin: String?,
    val preferredJersey: Short?,
    // Private: only for the member themself and management.
    val dateOfBirth: LocalDate?,
    val emergencyContact: EmergencyContact?,
    val consentPublic: Boolean?,
    val complete: Boolean,
    val missing: List<String>,
)

/** Full replace of the editable fields. The app keeps a draft and sends it after every step. */
data class ProfileUpdate(
    val favouredPosition: String? = null,
    val otherPositions: List<String> = emptyList(),
    val dominantFoot: Foot? = null,
    val weakFoot: Int? = null,
    val strengths: List<String> = emptyList(),
    val weaknesses: List<String> = emptyList(),
    val heightCm: Int? = null,
    val dateOfBirth: LocalDate? = null,
    val stateOfOrigin: String? = null,
    val preferredJersey: Int? = null,
    val emergencyName: String? = null,
    val emergencyPhone: String? = null,
    val consentPublic: Boolean? = null,
)

class Photo(val contentType: String, val data: ByteArray, val etag: String)

@Service
class ProfileService(
    private val profiles: MemberProfileRepository,
    private val media: MediaAssetRepository,
    private val members: MemberRepository,
    private val properties: CvgProperties,
    private val clock: Clock,
) {
    companion object {
        const val MAX_PHOTO_BYTES = 2_000_000
    }

    @Transactional(readOnly = true)
    fun find(memberId: UUID): MemberProfile? = profiles.findById(memberId).orElse(null)

    @Transactional(readOnly = true)
    fun view(memberId: UUID, includePrivate: Boolean): ProfileView {
        ensureMember(memberId)
        return toView(memberId, find(memberId), includePrivate)
    }

    @Transactional
    fun update(memberId: UUID, req: ProfileUpdate): ProfileView {
        val errors = linkedMapOf<String, String>()

        val favoured = req.favouredPosition?.let { Position.parse(it) ?: null.also { _ -> errors["favouredPosition"] = "Pick a position." } }
        val others = req.otherPositions.distinct()
        when {
            others.any { Position.parse(it) == null } -> errors["otherPositions"] = "Pick from the list."
            others.size > ProfileOptions.MAX_OTHER_POSITIONS -> errors["otherPositions"] = "Up to ${ProfileOptions.MAX_OTHER_POSITIONS}."
            favoured != null && favoured.name in others -> errors["otherPositions"] = "That's already your main position."
        }
        checkTraits("strengths", req.strengths, errors)
        checkTraits("weaknesses", req.weaknesses, errors)
        if (req.strengths.intersect(req.weaknesses.toSet()).isNotEmpty()) {
            errors["weaknesses"] = "Can't be a strength and a weakness."
        }
        if (req.weakFoot != null && req.weakFoot !in 1..5) errors["weakFoot"] = "1 to 5 stars."
        if (req.heightCm != null && req.heightCm !in 120..220) errors["heightCm"] = "Between 120 and 220 cm."
        if (req.preferredJersey != null && req.preferredJersey !in 1..99) errors["preferredJersey"] = "1–99 only."
        req.dateOfBirth?.let {
            val age = Period.between(it, LocalDate.now(clock)).years
            if (age !in 10..80) errors["dateOfBirth"] = "Check the date."
        }
        if (req.stateOfOrigin != null && req.stateOfOrigin !in ProfileOptions.states) errors["stateOfOrigin"] = "Pick from the list."
        val emergencyPhone = req.emergencyPhone?.takeIf { it.isNotBlank() }?.let {
            PhoneNumbers.normalise(it) ?: null.also { _ -> errors["emergencyPhone"] = "Enter a valid phone number." }
        }
        if (errors.isNotEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid", "Check the form.", errors)
        }

        ensureMember(memberId)
        val p = find(memberId) ?: MemberProfile(memberId)
        p.favouredPosition = favoured
        p.otherPositions = others
        p.dominantFoot = req.dominantFoot
        p.weakFoot = req.weakFoot?.toShort()
        p.strengths = req.strengths.distinct()
        p.weaknesses = req.weaknesses.distinct()
        p.heightCm = req.heightCm?.toShort()
        p.dateOfBirth = req.dateOfBirth
        p.stateOfOrigin = req.stateOfOrigin
        p.preferredJersey = req.preferredJersey?.toShort()
        p.emergencyName = req.emergencyName?.trim()?.takeIf { it.isNotEmpty() }
        p.emergencyPhone = emergencyPhone
        p.consentPublic = req.consentPublic
        touch(p)
        profiles.save(p)
        return toView(memberId, p, includePrivate = true)
    }

    /** Saves a new headshot. The app crops and resizes it first; we only check type and size. */
    @Transactional
    fun setPhoto(memberId: UUID, bytes: ByteArray): ProfileView {
        if (bytes.size > MAX_PHOTO_BYTES) {
            throw ApiException.badRequest("photo_too_big", "Photo is too big. Try another.")
        }
        val type = sniffImageType(bytes)
            ?: throw ApiException.badRequest("photo_type", "Use a JPG, PNG or WebP photo.")
        ensureMember(memberId)
        val p = find(memberId) ?: MemberProfile(memberId)
        val old = p.photoId
        val asset = media.save(MediaAsset(properties.clubId, type, bytes.size, bytes))
        p.photoId = asset.id
        touch(p)
        profiles.save(p)
        old?.let { media.deleteById(it) }
        return toView(memberId, p, includePrivate = true)
    }

    @Transactional(readOnly = true)
    fun photo(memberId: UUID): Photo? {
        val photoId = find(memberId)?.photoId ?: return null
        val asset = media.findById(photoId).orElse(null) ?: return null
        return Photo(asset.contentType, asset.data, "\"${asset.id}\"")
    }

    private fun touch(p: MemberProfile) {
        val now = Instant.now(clock)
        p.updatedAt = now
        if (p.completedAt == null && p.missing().isEmpty()) p.completedAt = now
    }

    private fun ensureMember(memberId: UUID) {
        members.findByIdAndClubId(memberId, properties.clubId) ?: throw ApiException.notFound("Member")
    }

    private fun checkTraits(field: String, values: List<String>, errors: MutableMap<String, String>) {
        when {
            values.any { it !in ProfileOptions.traits } -> errors[field] = "Pick from the list."
            values.distinct().size > ProfileOptions.MAX_TRAITS -> errors[field] = "Up to ${ProfileOptions.MAX_TRAITS}."
        }
    }

    fun toView(memberId: UUID, p: MemberProfile?, includePrivate: Boolean): ProfileView {
        val missing = p?.missing() ?: listOf("photo", "favouredPosition", "dominantFoot", "emergencyContact", "consent")
        return ProfileView(
            memberId = memberId,
            photoUrl = p?.photoId?.let { photoUrl(memberId, it) },
            favouredPosition = p?.favouredPosition,
            positionGroup = p?.favouredPosition?.group,
            otherPositions = p?.otherPositions.orEmpty(),
            dominantFoot = p?.dominantFoot,
            weakFoot = p?.weakFoot,
            strengths = p?.strengths.orEmpty(),
            weaknesses = p?.weaknesses.orEmpty(),
            heightCm = p?.heightCm,
            age = p?.dateOfBirth?.let { Period.between(it, LocalDate.now(clock)).years },
            stateOfOrigin = p?.stateOfOrigin,
            preferredJersey = p?.preferredJersey,
            dateOfBirth = if (includePrivate) p?.dateOfBirth else null,
            emergencyContact = if (includePrivate && p?.emergencyName != null && p.emergencyPhone != null) {
                EmergencyContact(p.emergencyName!!, PhoneNumbers.display(p.emergencyPhone!!))
            } else {
                null
            },
            consentPublic = if (includePrivate) p?.consentPublic else null,
            complete = missing.isEmpty(),
            missing = if (includePrivate) missing else emptyList(),
        )
    }
}

/** Relative URL the apps can use directly (proxied); the version busts caches when the photo changes. */
fun photoUrl(memberId: UUID, photoId: UUID) = "/api/members/$memberId/photo?v=${photoId.toString().take(8)}"

/** Identifies JPEG, PNG and WebP by their first bytes; never trust the declared type. */
fun sniffImageType(b: ByteArray): String? {
    fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
    return when {
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
        b.size >= 12 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP" -> "image/webp"
        else -> null
    }
}
