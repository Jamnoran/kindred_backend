package com.kindred.api.chat

import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-instance WebSocket presence (a user may have several sessions/tabs). Like the
 * simple broker, this is single-instance state — moving it to Redis is part of
 * scaling out, tracked in FEATURES.
 */
@Component
class PresenceTracker {

    private val sessions = ConcurrentHashMap<Long, Int>()

    /** @return true when this was the user's first open session (offline → online) */
    fun connected(userId: Long): Boolean = sessions.merge(userId, 1, Int::plus) == 1

    /** @return true when this was the user's last open session (online → offline) */
    fun disconnected(userId: Long): Boolean =
        sessions.computeIfPresent(userId) { _, count -> if (count <= 1) null else count - 1 } == null

    fun isOnline(userId: Long): Boolean = sessions.containsKey(userId)
}

@Component
class PresenceEventListener(
    private val tracker: PresenceTracker,
    private val chatService: ChatService,
) {

    @EventListener
    fun onConnected(event: SessionConnectedEvent) {
        val userId = userIdOf(event.message.headers) ?: return
        if (tracker.connected(userId)) {
            broadcastPresence(userId, online = true)
        }
    }

    @EventListener
    fun onDisconnected(event: SessionDisconnectEvent) {
        val userId = userIdOf(event.message.headers) ?: return
        if (tracker.disconnected(userId)) {
            broadcastPresence(userId, online = false)
        }
    }

    private fun broadcastPresence(userId: Long, online: Boolean) {
        chatService.conversationIdsOf(userId).forEach { conversationId ->
            chatService.broadcast(
                ChatEvent(type = "presence", conversationId = conversationId, presenceUserId = userId, online = online),
            )
        }
    }

    private fun userIdOf(headers: org.springframework.messaging.MessageHeaders): Long? {
        val accessor = StompHeaderAccessor.wrap(org.springframework.messaging.support.MessageBuilder.createMessage(ByteArray(0), headers))
        return runCatching { SubscriptionAuthInterceptor.userIdOf(accessor.user) }.getOrNull()
    }
}
