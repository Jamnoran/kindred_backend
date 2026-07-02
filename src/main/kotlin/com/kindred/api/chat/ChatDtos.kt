package com.kindred.api.chat

import com.kindred.api.discovery.PhotoSummary
import jakarta.validation.constraints.Size
import java.time.Instant

class ConversationNotFoundException : RuntimeException("conversation not found")

class EmptyMessageException : RuntimeException("a message needs a body, a mediaKey, or both")

data class SendMessageRequest(
    @field:Size(max = 2000)
    val body: String? = null,
    /** chat-quarantine key from POST /conversations/{id}/media-uploads */
    @field:Size(max = 255)
    val mediaKey: String? = null,
)

data class MessageResponse(
    val id: Long,
    val senderId: Long,
    val body: String?,
    /** fetch the image via GET /api/v1/media/{mediaId}/url once approved */
    val mediaId: Long?,
    val createdAt: Instant,
    val readAt: Instant?,
) {
    companion object {
        fun from(m: Message) = MessageResponse(
            id = requireNotNull(m.id),
            senderId = m.senderId,
            body = m.body,
            mediaId = m.mediaId,
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
