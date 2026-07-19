package com.kindred.api.notification

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Per-recipient-per-conversation cooldown so a burst of messages becomes one
 * notification. Check-then-set (marked only after a successful send) so a failed
 * send stays retryable by JobRunr; the tiny race between two concurrent jobs can
 * at worst duplicate one notification, never drop one.
 */
@Service
@Profile("!openapi")
class NotificationThrottle(
    private val redis: StringRedisTemplate,
    @param:Value("\${kindred.notifications.message-throttle:15m}") private val messageThrottle: Duration,
) {

    companion object {
        const val KEY_PREFIX = "kindred:notify:message:"
    }

    private fun key(recipientId: Long, conversationId: Long) = "$KEY_PREFIX$recipientId:$conversationId"

    fun isThrottled(recipientId: Long, conversationId: Long): Boolean =
        redis.hasKey(key(recipientId, conversationId))

    fun markNotified(recipientId: Long, conversationId: Long) {
        redis.opsForValue().set(key(recipientId, conversationId), "1", messageThrottle)
    }
}
