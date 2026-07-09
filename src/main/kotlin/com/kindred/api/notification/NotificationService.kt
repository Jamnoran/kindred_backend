package com.kindred.api.notification

import com.kindred.api.auth.UserRepository
import com.kindred.api.chat.MessageRepository
import com.kindred.api.chat.PresenceService
import com.kindred.api.discovery.Match
import com.kindred.api.profile.ProfileRepository
import org.jobrunr.scheduling.JobRequestScheduler
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/**
 * Offline notifications: tells users about matches and messages they would otherwise
 * miss because they aren't on the site. Producers (LikeService, ChatService) enqueue a
 * JobRunr job inside their transaction; [dispatch] runs in the worker and re-checks
 * every precondition *then*, not at enqueue time — the recipient may have come online
 * or read the conversation in between.
 */
@Service
class NotificationService(
    private val users: UserRepository,
    private val profiles: ProfileRepository,
    private val messages: MessageRepository,
    private val preferences: NotificationPreferenceService,
    private val channels: List<NotificationChannel>,
    private val jobs: JobRequestScheduler,
    // absent when Redis is (openapi spec-export boot, slice tests) — then everyone
    // reads as offline, which is moot because that boot never runs jobs
    private val presence: ObjectProvider<PresenceService>,
    private val throttle: ObjectProvider<NotificationThrottle>,
) {

    /** The reactor just saw the match in their response; only the other side is notified. */
    fun matchCreated(reactorId: Long, match: Match, conversationId: Long) {
        jobs.enqueue(
            SendNotificationRequest(
                type = NotificationType.new_match,
                recipientUserId = match.otherThan(reactorId),
                otherUserId = reactorId,
                conversationId = conversationId,
            ),
        )
    }

    fun messageSent(senderId: Long, recipientId: Long, conversationId: Long) {
        jobs.enqueue(
            SendNotificationRequest(
                type = NotificationType.new_message,
                recipientUserId = recipientId,
                otherUserId = senderId,
                conversationId = conversationId,
            ),
        )
    }

    /** Runs in the JobRunr worker. A throw makes JobRunr retry the whole notification. */
    fun dispatch(request: SendNotificationRequest) {
        if (presence.ifAvailable?.isOnline(request.recipientUserId) == true) return
        if (request.type == NotificationType.new_message) {
            if (throttle.ifAvailable?.isThrottled(request.recipientUserId, request.conversationId) == true) return
            // already read (or deleted) by now → nothing to tell
            if (messages.countByConversationIdAndSenderIdNotAndReadAtIsNull(request.conversationId, request.recipientUserId) == 0L) return
        }
        val recipient = users.findById(request.recipientUserId).orElse(null)
            ?.takeIf { it.deletedAt == null } ?: return
        val other = profiles.findById(request.otherUserId).orElse(null) ?: return
        val enabled = preferences.enabledChannels(request.recipientUserId, request.type)
        val targets = channels.filter { it.channelType in enabled }
        if (targets.isEmpty()) return

        val notification = OfflineNotification(
            type = request.type,
            recipientUserId = request.recipientUserId,
            recipientEmail = recipient.email,
            otherDisplayName = other.displayName,
            conversationId = request.conversationId,
        )
        targets.forEach { it.send(notification) }
        if (request.type == NotificationType.new_message) {
            throttle.ifAvailable?.markNotified(request.recipientUserId, request.conversationId)
        }
    }
}
