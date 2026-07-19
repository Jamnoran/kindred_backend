package com.kindred.api.chat

import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/**
 * Multi-instance online tracking (§8). Each user gets a Redis ZSET of their live
 * WebSocket sessions, scored by last-seen time. Sessions are added on connect,
 * refreshed periodically by every instance for its own connections (PresenceTracker),
 * and removed on disconnect — and because a crashed instance stops refreshing, its
 * sessions age past ONLINE_WINDOW and its users read as offline without any cleanup
 * job. The key TTL is just garbage collection for users who never reconnect.
 */
@Service
@Profile("!openapi")
class PresenceService(
    private val redis: StringRedisTemplate,
    private val clock: Clock,
) {

    companion object {
        /** A session counts as online while its last refresh is younger than this. */
        val ONLINE_WINDOW: Duration = Duration.ofMinutes(5)
        val KEY_TTL: Duration = Duration.ofMinutes(30)
        const val KEY_PREFIX = "kindred:presence:"
    }

    private fun key(userId: Long) = "$KEY_PREFIX$userId"

    fun markOnline(userId: Long, sessionId: String) {
        val k = key(userId)
        redis.opsForZSet().add(k, sessionId, clock.instant().epochSecond.toDouble())
        redis.expire(k, KEY_TTL)
    }

    fun markOffline(userId: Long, sessionId: String) {
        redis.opsForZSet().remove(key(userId), sessionId)
    }

    fun isOnline(userId: Long): Boolean {
        val k = key(userId)
        // prune sessions whose instance stopped refreshing (crash, lost disconnect)
        redis.opsForZSet().removeRangeByScore(
            k,
            Double.NEGATIVE_INFINITY,
            clock.instant().minus(ONLINE_WINDOW).epochSecond.toDouble(),
        )
        return (redis.opsForZSet().size(k) ?: 0L) > 0L
    }

    fun onlineOf(userIds: Collection<Long>): Set<Long> =
        userIds.filterTo(mutableSetOf()) { isOnline(it) }
}
