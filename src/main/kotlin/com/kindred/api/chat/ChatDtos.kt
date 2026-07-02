package com.kindred.api.chat

import com.kindred.api.discovery.PhotoSummary
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

class ConversationNotFoundException : RuntimeException("conversation not found")

data class SendMessageRequest(
    @field:NotBlank @field:Size(max = 2000)
    val body: String,
)

data class MessageResponse(
    val id: Long,
    val senderId: Long,
    val body: String?,
    val createdAt: Instant,
    val readAt: Instant?,
) {
    companion object {
        fun from(m: Message) = MessageResponse(
            id = requireNotNull(m.id),
            senderId = m.senderId,
            body = m.body,
            createdAt = m.createdAt,
            readAt = m.readAt,
        )
    }
}

data class ConversationParticipant(
    val userId: Long,
    val displayName: String,
    val photo: PhotoSummary?,
)

data class ConversationResponse(
    val id: Long,
    val matchId: Long,
    val matchedAt: Instant,
    val otherUser: ConversationParticipant,
    val lastMessage: MessageResponse?,
    val unreadCount: Long,
)
