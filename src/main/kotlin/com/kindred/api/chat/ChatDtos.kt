package com.kindred.api.chat

import com.kindred.api.discovery.PhotoSummary
import com.kindred.api.media.PresignedUpload
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.PhotoUrls
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

class ConversationNotFoundException : RuntimeException("conversation not found")

class EmptyMessageException : RuntimeException("message needs a body, an image, or both")

/** Also used when the media was rejected — indistinguishable from never existing. */
class ChatMediaNotFoundException : RuntimeException("media not found")

class ChatMediaNotReadyException : RuntimeException("media is still processing")

data class SendMessageRequest(
    @field:Size(max = 2000)
    val body: String? = null,
    /** Quarantine key from POST /conversations/{id}/media-uploads; needs body or this. */
    @field:Size(max = 255)
    val mediaStorageKey: String? = null,
)

data class ChatMediaUploadRequest(
    @field:NotBlank
    val contentType: String,
)

data class ChatMediaUploadResponse(
    val uploadUrl: String,
    val storageKey: String,
    val expiresAt: Instant,
) {
    companion object {
        fun from(upload: PresignedUpload) =
            ChatMediaUploadResponse(upload.uploadUrl, upload.storageKey, upload.expiresAt)
    }
}

/** What messages carry about an attached image; bytes come via the signed-URL endpoint. */
data class ChatMediaSummary(
    val id: Long,
    val status: ModerationStatus,
    /** true → render the blurhash and require an explicit tap before fetching the signed URLs */
    val nsfw: Boolean,
    val blurhash: String?,
) {
    companion object {
        fun from(media: ChatMedia?): ChatMediaSummary? = media?.let {
            ChatMediaSummary(requireNotNull(it.id), it.moderationStatus, it.isNsfw, it.blurhash)
        }
    }
}

/** Short-lived signed URLs (§6B) — refetch after `expiresAt`, never cache long-term. */
data class ChatMediaUrlsResponse(
    val mediaId: Long,
    val urls: PhotoUrls,
    val expiresAt: Instant,
)

data class MessageResponse(
    val id: Long,
    val senderId: Long,
    val body: String?,
    val media: ChatMediaSummary?,
    val createdAt: Instant,
    val readAt: Instant?,
) {
    companion object {
        fun from(m: Message, media: ChatMedia? = null) = MessageResponse(
            id = requireNotNull(m.id),
            senderId = m.senderId,
            body = m.body,
            media = ChatMediaSummary.from(media),
            createdAt = m.createdAt,
            readAt = m.readAt,
        )
    }
}

data class ConversationParticipant(
    val userId: Long,
    val displayName: String,
    val photo: PhotoSummary?,
    val online: Boolean,
)

data class ConversationResponse(
    val id: Long,
    val matchId: Long,
    val matchedAt: Instant,
    val otherUser: ConversationParticipant,
    val lastMessage: MessageResponse?,
    val unreadCount: Long,
)
