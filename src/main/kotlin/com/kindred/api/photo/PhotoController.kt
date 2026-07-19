package com.kindred.api.photo

import com.kindred.api.auth.KindredUserDetails
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/photos")
class PhotoController(
    private val photoService: PhotoService,
    @param:Value("\${kindred.media.public-base-url}") private val publicBaseUrl: String,
) {

    /** Submit an uploaded quarantine object for processing; poll GET /photos for status. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun submit(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @Valid @RequestBody req: CreatePhotoRequest,
    ): PhotoResponse = PhotoResponse.from(photoService.submit(principal.id, req.storageKey), publicBaseUrl)

    @GetMapping
    fun list(@AuthenticationPrincipal principal: KindredUserDetails): List<PhotoResponse> =
        photoService.listOwn(principal.id).map { PhotoResponse.from(it, publicBaseUrl) }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal principal: KindredUserDetails, @PathVariable id: Long) {
        photoService.delete(principal.id, id)
    }
}
