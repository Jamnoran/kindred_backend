package com.kindred.api.chat

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.media.PresignedDownload
import jakarta.validation.constraints.NotBlank
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class ChatMediaUploadRequest(
    @field:NotBlank
    val contentType: String,
)

@RestController
@RequestMapping("/api/v1/media")
class ChatMediaController(private val chatMediaService: ChatMediaService) {

    /** 5-minute signed URL for an approved chat image; participants only, checked per fetch. */
    @GetMapping("/{id}/url")
    fun signedUrl(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @PathVariable id: Long,
    ): PresignedDownload = chatMediaService.signedUrl(principal.id, id)
}
