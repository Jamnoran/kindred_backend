package com.kindred.api.chat

import com.kindred.api.discovery.Match
import com.kindred.api.discovery.MatchRepository
import com.kindred.api.discovery.PhotoSummary
import com.kindred.api.photo.InvalidStorageKeyException
import com.kindred.api.premium.PremiumRequiredException
import com.kindred.api.premium.PremiumService
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.Photo
import com.kindred.api.photo.PhotoRepository
import com.kindred.api.profile.ProfileRepository
import org.jobrunr.scheduling.JobRequestScheduler
import org.springframework.beans.factory.ObjectProvider
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
    private val chatMedia: ChatMediaRepository,
    private val premium: PremiumService,
    private val jobs: JobRequestScheduler,
    private val clock: Clock,
    // absent when Redis is (openapi spec-export boot, slice tests) — broadcast no-ops
    private val relay: ObjectProvider<ChatEventRelay>,
    // absent for the same reason — everyone just reads as offline
    private val presence: ObjectProvider<PresenceService>,
    @param:Value("\${kindred.media.public-base-url}") private val publicBaseUrl: String,
) {

    companion object {
        private val QUARANTINE_KEY = Regex("quarantine/[0-9a-f]{32}")
    }

    @Transactional(readOnly = true)
    fun listConversations(userId: Long): List<ConversationResponse> {
        val myMatches = matches.findAllByUserAOrUserB(userId, userId).associateBy { requireNotNull(it.id) }
        if (myMatches.isEmpty()) return emptyList()
        val convos = conversations.findAllByMatchIdIn(myMatches.keys)
        val otherIds = convos.mapNotNull { myMatches[it.matchId]?.otherThan(userId) }
        val profilesById = profiles.findAllById(otherIds).associateBy { it.userId }
        val primaryPhotos = photos.findAllByProfileUserIdInAndModerationStatusAndIsPrimaryTrue(otherIds, ModerationStatus.approved)
            .associateBy(Photo::profileUserId)
        val onlineIds = presence.ifAvailable?.onlineOf(otherIds) ?: emptySet()
        val premiumIds = premium.premiumIdsOf(otherIds + userId)

        return convos.mapNotNull { convo ->
            val match = myMatches[convo.matchId] ?: return@mapNotNull null
            val otherId = match.otherThan(userId)
            val profile = profilesById[otherId] ?: return@mapNotNull null
            val convoId = requireNotNull(convo.id)
            ConversationResponse(
                id = convoId,
                matchId = requireNotNull(match.id),
                matchedAt = match.createdAt,
                imageMessagingEnabled = userId in premiumIds || otherId in premiumIds,
                otherUser = ConversationParticipant(
                    userId = otherId,
                    displayName = profile.displayName,
                    photo = PhotoSummary.from(primaryPhotos[otherId], publicBaseUrl),
                    online = otherId in onlineIds,
                ),
                lastMessage = messages.findFirstByConversationIdOrderByIdDesc(convoId)?.let { toResponse(it) },
                unreadCount = messages.countByConversationIdAndSenderIdNotAndReadAtIsNull(convoId, userId),
            )
        }.sortedByDescending { it.lastMessage?.createdAt ?: it.matchedAt }
    }

    @Transactional(readOnly = true)
    fun messages(userId: Long, conversationId: Long, before: Long?, limit: Int): List<MessageResponse> {
        requireMembership(userId, conversationId)
        val page = messages.page(conversationId, before, PageRequest.of(0, limit))
        val mediaById = chatMedia.findAllById(page.mapNotNull(Message::mediaId)).associateBy { requireNotNull(it.id) }
        return page.map { MessageResponse.from(it, it.mediaId?.let(mediaById::get)) }
    }

    @Transactional
    fun send(userId: Long, conversationId: Long, body: String?, mediaStorageKey: String? = null): MessageResponse {
        val match = requireMembership(userId, conversationId)
        val trimmed = body?.trim().takeUnless { it.isNullOrEmpty() }
        if (trimmed == null && mediaStorageKey == null) throw EmptyMessageException()
        if (mediaStorageKey != null) requirePremiumParticipant(match)

        val media = mediaStorageKey?.let { submitMedia(userId, conversationId, it) }
        val message = messages.save(
            Message(
                conversationId = conversationId,
                senderId = userId,
                body = trimmed,
                mediaId = media?.id,
                createdAt = clock.instant(),
            ),
        )
        media?.let { jobs.enqueue(ProcessChatMediaRequest(requireNotNull(it.id))) }
        val response = MessageResponse.from(message, media)
        broadcast(ChatEvent(type = "message", conversationId = conversationId, message = response))
        return response
    }

    /**
     * Records the §6B media row (pending — no bytes are served until the worker
     * validates, scans, and promotes it out of quarantine).
     */
    private fun submitMedia(userId: Long, conversationId: Long, storageKey: String): ChatMedia {
        if (!QUARANTINE_KEY.matches(storageKey)) {
            throw InvalidStorageKeyException("mediaStorageKey must be one returned by the media-uploads endpoint")
        }
        if (chatMedia.existsByStorageKey(storageKey) || photos.existsByStorageKey(storageKey)) {
            throw InvalidStorageKeyException("mediaStorageKey was already submitted")
        }
        return chatMedia.save(
            ChatMedia(
                storageKey = storageKey,
                ownerUserId = userId,
                conversationId = conversationId,
                createdAt = clock.instant(),
            ),
        )
    }

    /** Marks everything from the other participant as read; returns how many changed. */
    @Transactional
    fun markRead(userId: Long, conversationId: Long): Int {
        requireMembership(userId, conversationId)
        val changed = messages.markRead(conversationId, userId, clock.instant())
        if (changed > 0) {
            broadcast(ChatEvent(type = "read", conversationId = conversationId, readerId = userId))
        }
        return changed
    }

    /** Fans out via Redis so subscribers on every API instance see the event. */
    fun broadcast(event: ChatEvent) {
        relay.ifAvailable?.publish(event)
    }

    /**
     * AuthZ on every read and send (§8): non-membership is indistinguishable from
     * nonexistence, so conversation ids can't be probed.
     */
    @Transactional(readOnly = true)
    fun requireMembership(userId: Long, conversationId: Long): Match {
        val convo = conversations.findById(conversationId).orElseThrow { ConversationNotFoundException() }
        val match = matches.findById(convo.matchId).orElseThrow { ConversationNotFoundException() }
        if (!match.involves(userId)) throw ConversationNotFoundException()
        return match
    }

    /** Membership + the premium gate for image messaging (also guards the upload presign). */
    @Transactional(readOnly = true)
    fun requireImageMessaging(userId: Long, conversationId: Long) {
        requirePremiumParticipant(requireMembership(userId, conversationId))
    }

    /** Images are premium: allowed for both sides as long as *either* participant upgraded. */
    private fun requirePremiumParticipant(match: Match) {
        if (!premium.anyPremium(listOf(match.userA, match.userB))) {
            throw PremiumRequiredException("sending images in this chat")
        }
    }

    private fun toResponse(message: Message): MessageResponse =
        MessageResponse.from(message, message.mediaId?.let { chatMedia.findById(it).orElse(null) })
}
