package com.kindred.api.chat

import com.kindred.api.discovery.MatchRepository
import com.kindred.api.discovery.PhotoSummary
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.Photo
import com.kindred.api.photo.PhotoRepository
import com.kindred.api.profile.ProfileRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
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
    private val clock: Clock,
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
                ),
                lastMessage = messages.findFirstByConversationIdOrderByIdDesc(convoId)?.let(MessageResponse::from),
                unreadCount = messages.countByConversationIdAndSenderIdNotAndReadAtIsNull(convoId, userId),
            )
        }.sortedByDescending { it.lastMessage?.createdAt ?: it.matchedAt }
    }

    @Transactional(readOnly = true)
    fun messages(userId: Long, conversationId: Long, before: Long?, limit: Int): List<MessageResponse> {
        requireMembership(userId, conversationId)
        return messages.page(conversationId, before, PageRequest.of(0, limit)).map(MessageResponse::from)
    }

    @Transactional
    fun send(userId: Long, conversationId: Long, body: String): MessageResponse {
        requireMembership(userId, conversationId)
        val message = messages.save(
            Message(
                conversationId = conversationId,
                senderId = userId,
                body = body.trim(),
                createdAt = clock.instant(),
            ),
        )
        return MessageResponse.from(message)
    }

    /** Marks everything from the other participant as read; returns how many changed. */
    @Transactional
    fun markRead(userId: Long, conversationId: Long): Int {
        requireMembership(userId, conversationId)
        return messages.markRead(conversationId, userId, clock.instant())
    }

    /**
     * AuthZ on every read and send (§8): non-membership is indistinguishable from
     * nonexistence, so conversation ids can't be probed.
     */
    @Transactional(readOnly = true)
    fun requireMembership(userId: Long, conversationId: Long) {
        val convo = conversations.findById(conversationId).orElseThrow { ConversationNotFoundException() }
        val match = matches.findById(convo.matchId).orElseThrow { ConversationNotFoundException() }
        if (!match.involves(userId)) throw ConversationNotFoundException()
    }
}
