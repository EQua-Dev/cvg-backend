package ng.cvgfc.api.profile

import ng.cvgfc.api.auth.CurrentMember
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Duration
import java.util.UUID

data class PositionOption(val code: String, val label: String, val group: PositionGroup)

data class ProfileOptionsView(
    val positions: List<PositionOption>,
    val feet: List<Foot>,
    val traits: List<String>,
    val states: List<String>,
    val maxOtherPositions: Int,
    val maxTraits: Int,
)

@RestController
class ProfileController(private val service: ProfileService) {

    @GetMapping("/api/profile/options")
    fun options() = ProfileOptionsView(
        positions = Position.entries.map { PositionOption(it.name, it.label, it.group) },
        feet = Foot.entries,
        traits = ProfileOptions.traits,
        states = ProfileOptions.states,
        maxOtherPositions = ProfileOptions.MAX_OTHER_POSITIONS,
        maxTraits = ProfileOptions.MAX_TRAITS,
    )

    @GetMapping("/api/me/profile")
    fun mine(@AuthenticationPrincipal me: CurrentMember) = service.view(me.id, includePrivate = true)

    @PutMapping("/api/me/profile")
    fun update(@RequestBody req: ProfileUpdate, @AuthenticationPrincipal me: CurrentMember) =
        service.update(me.id, req)

    @PutMapping("/api/me/photo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun photo(@RequestParam("file") file: MultipartFile, @AuthenticationPrincipal me: CurrentMember) =
        service.setPhoto(me.id, file.bytes)

    /** Squad-mates see football details; private details only for the member and management. */
    @GetMapping("/api/members/{id}/profile")
    fun ofMember(@PathVariable id: UUID, @AuthenticationPrincipal me: CurrentMember) =
        service.view(id, includePrivate = me.isManagement || me.id == id)

    @GetMapping("/api/members/{id}/photo")
    fun memberPhoto(
        @PathVariable id: UUID,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?,
    ): ResponseEntity<ByteArray> = photoResponse(service.photo(id), ifNoneMatch, public = false)
}

fun photoResponse(photo: Photo?, ifNoneMatch: String?, public: Boolean): ResponseEntity<ByteArray> {
    if (photo == null) return ResponseEntity.notFound().build()
    val cache = CacheControl.maxAge(Duration.ofDays(7)).let { if (public) it.cachePublic() else it.cachePrivate() }
    if (ifNoneMatch == photo.etag) return ResponseEntity.status(304).cacheControl(cache).eTag(photo.etag).build()
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(photo.contentType))
        .cacheControl(cache)
        .eTag(photo.etag)
        .body(photo.data)
}
