package com.kindred.api.media

import com.kindred.api.auth.KindredUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class ProfilePhotoUploadRequest(
    @field:NotBlank
    val contentType: String,
)

data class ProfilePhotoUploadResponse(
    val uploadUrl: String,
    val storageKey: String,
    val expiresAt: Instant,
) {
    companion object {
        fun from(upload: PresignedUpload) =
            ProfilePhotoUploadResponse(upload.uploadUrl, upload.storageKey, upload.expiresAt)
    }
}

@RestController
@RequestMapping("/api/v1/media")
class MediaUploadController(private val mediaUploadService: MediaUploadService) {

    /** PUT the image bytes to `uploadUrl` with the same Content-Type used here. */
    @PostMapping("/profile-photo-uploads")
    @ResponseStatus(HttpStatus.CREATED)
    fun createProfilePhotoUpload(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @Valid @RequestBody req: ProfilePhotoUploadRequest,
    ): ProfilePhotoUploadResponse =
        ProfilePhotoUploadResponse.from(mediaUploadService.presignProfilePhotoUpload(principal.id, req.contentType))
}
