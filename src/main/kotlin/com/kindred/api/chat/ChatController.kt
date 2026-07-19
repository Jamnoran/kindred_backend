package com.kindred.api.chat

import com.kindred.api.auth.KindredUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/conversations")
class ChatController(
    private val chatService: ChatService,
    private val chatMediaService: ChatMediaService,
) {

    @GetMapping
    fun conversations(@AuthenticationPrincipal principal: KindredUserDetails): List<ConversationResponse> =
        chatService.listConversations(principal.id)

    @GetMapping("/{id}/messages")
    fun messages(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @PathVariable id: Long,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
    ): List<MessageResponse> = chatService.messages(principal.id, id, before, limit)

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    fun send(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @PathVariable id: Long,
        @Valid @RequestBody req: SendMessageRequest,
    ): MessageResponse = chatService.send(principal.id, id, req.body, req.mediaStorageKey)

    /** PUT the image bytes to `uploadUrl`, then send a message with the returned storageKey. */
    @PostMapping("/{id}/media-uploads")
    @ResponseStatus(HttpStatus.CREATED)
    fun createMediaUpload(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @PathVariable id: Long,
        @Valid @RequestBody req: ChatMediaUploadRequest,
    ): ChatMediaUploadResponse =
        ChatMediaUploadResponse.from(chatMediaService.presignUpload(principal.id, id, req.contentType))

    /** Short-lived signed URLs for a chat image (§6B) — authorized on every fetch. */
    @GetMapping("/{id}/media/{mediaId}")
    fun mediaUrls(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @PathVariable id: Long,
        @PathVariable mediaId: Long,
    ): ChatMediaUrlsResponse = chatMediaService.signedUrls(principal.id, id, mediaId)

    /** Read receipt: marks the other participant's messages as read. */
    @PostMapping("/{id}/read")
    fun markRead(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @PathVariable id: Long,
    ): Map<String, Int> = mapOf("markedRead" to chatService.markRead(principal.id, id))
}
