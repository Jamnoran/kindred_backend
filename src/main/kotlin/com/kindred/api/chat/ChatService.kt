package com.kindred.api.chat

import com.kindred.api.discovery.MatchRepository
import com.kindred.api.discovery.PhotoSummary
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.Photo
import com.kindred.api.photo.PhotoRepository
import com.kindred.api.profile.ProfileRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ChatService(
    private val conversations: ConversationRepository,
    private val messages: MessageRepository,
    private val matches: MatchRepository,
    private val profiles: ProfileRepository,
    private val photos: PhotoRepository,
    private val access: ConversationAccess,
    private val chatMedia: ChatMediaService,
    private val presence: PresenceTracker,
    private val clock: Clock,
    // lazy to break the cycle with WebSocketConfig; absent in slice tests
    private val messaging: ObjectProvider<SimpMessagingTemplate>,
    private val relay: ObjectProvider<ChatEventPublisher>,
    @param:Value("\${kindred.media.public-base-url}") private val publicBaseUrl: String,
) {

    @Transactional(readOnly = true)
    fun listConversations(userId: Long): List<ConversationResponse> {
        val myMatches = matches.findAllByUserAOrUserB(userId, userId).associateBy { requireNotNull(it.id) }
        if (myMatches.isEmpty()) return emptyList()
        val convos = conversations.findAllByMatchIdIn(myMatches.keys)
        val otherIds = convos.mapNotNull { myMatches[it.matchId]?.otherThan(userId) }
        val profilesById = profiles.findAllById(otherIds).associateBy { it.userId }
        val primaryPhotos = photos.findAllByProfileUserIdInAndModerationStatusAndIsPrimaryTrue(otherIds, ModerationStatus.approved)
            .associateBy(Photo::profileUserId)

        return convos.mapNotNull { convo ->
            val match = myMatches[convo.matchId] ?: return@mapNotNull null
            val otherId = match.otherThan(userId)
            val profile = profilesById[otherId] ?: return@mapNotNull null
            val convoId = requireNotNull(convo.id)
            ConversationResponse(
                id = convoId,
                matchId = requireNotNull(match.id),
                matchedAt = match.createdAt,
                otherUser = ConversationParticipant(
                    userId = otherId,
                    displayName = profile.displayName,
                    photo = PhotoSummary.from(primaryPhotos[otherId], publicBaseUrl),
                    online = presence.isOnline(otherId),
                ),
                lastMessage = messages.findFirstByConversationIdOrderByIdDesc(convoId)?.let(MessageResponse::from),
                unreadCount = messages.countByConversationIdAndSenderIdNotAndReadAtIsNull(convoId, userId),
            )
        }.sortedByDescending { it.lastMessage?.createdAt ?: it.matchedAt }
    }

    @Transactional(readOnly = true)
    fun messages(userId: Long, conversationId: Long, before: Long?, limit: Int): List<MessageResponse> {
        access.requireMembership(userId, conversationId)
        return messages.page(conversationId, before, PageRequest.of(0, limit)).map(MessageResponse::from)
    }

    /** Text, image, or both — an image goes through the §6B pipeline before it's viewable. */
    @Transactional
    fun send(userId: Long, conversationId: Long, body: String?, mediaKey: String? = null): MessageResponse {
        access.requireMembership(userId, conversationId)
        val trimmed = body?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmed == null && mediaKey == null) {
            throw EmptyMessageException()
        }
        val attached = mediaKey?.let { chatMedia.attach(userId, conversationId, it) }
        val message = messages.save(
            Message(
                conversationId = conversationId,
                senderId = userId,
                body = trimmed,
                mediaId = attached?.id,
                createdAt = clock.instant(),
            ),
        )
        val response = MessageResponse.from(message)
        broadcast(ChatEvent(type = "message", conversationId = conversationId, message = response))
        return response
    }

    /** Marks everything from the other participant as read; returns how many changed. */
    @Transactional
    fun markRead(userId: Long, conversationId: Long): Int {
        access.requireMembership(userId, conversationId)
        val changed = messages.markRead(conversationId, userId, clock.instant())
        if (changed > 0) {
            broadcast(ChatEvent(type = "read", conversationId = conversationId, readerId = userId))
        }
        return changed
    }

    /** Via Redis when the relay is up (multi-instance fan-out), else the local broker. */
    fun broadcast(event: ChatEvent) {
        val publisher = relay.ifAvailable
        if (publisher != null) {
            publisher.publish(event)
        } else {
            messaging.ifAvailable?.convertAndSend("/topic/conversations/${event.conversationId}", event)
        }
    }

    fun conversationIdsOf(userId: Long): List<Long> {
        val matchIds = matches.findAllByUserAOrUserB(userId, userId).mapNotNull { it.id }
        if (matchIds.isEmpty()) return emptyList()
        return conversations.findAllByMatchIdIn(matchIds).mapNotNull { it.id }
    }
}
