package com.kindred.api.chat

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.discovery.MatchRepository
import com.kindred.api.profile.ProfileRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.security.Principal
import java.time.Clock

/**
 * Feeds PresenceService from the WebSocket session lifecycle. Connect/disconnect
 * events cover the normal path; the scheduled sweep re-registers every session
 * still connected to *this* instance so entries stay fresh (and a crashed
 * instance's entries age out — see PresenceService). Presence changes are pushed
 * as `presence` events on all of the user's conversation topics, so anyone with
 * an open chat sees the dot flip live.
 */
@Component
@Profile("!openapi")
class PresenceTracker(
    private val presence: PresenceService,
    private val userRegistry: SimpUserRegistry,
    private val profiles: ProfileRepository,
    private val matches: MatchRepository,
    private val conversations: ConversationRepository,
    private val relay: ObjectProvider<ChatEventRelay>,
    private val clock: Clock,
) {

    @EventListener
    fun onConnected(event: SessionConnectedEvent) {
        val userId = userIdOf(event.user) ?: return
        val sessionId = SimpMessageHeaderAccessor.getSessionId(event.message.headers) ?: return
        val cameOnline = !presence.isOnline(userId)
        presence.markOnline(userId, sessionId)
        touchLastActive(userId)
        if (cameOnline) broadcastPresence(userId, online = true)
    }

    @EventListener
    fun onDisconnect(event: SessionDisconnectEvent) {
        val userId = userIdOf(event.user) ?: return
        presence.markOffline(userId, event.sessionId)
        if (!presence.isOnline(userId)) broadcastPresence(userId, online = false)
    }

    /** Keeps this instance's live sessions inside PresenceService.ONLINE_WINDOW. */
    @Scheduled(fixedDelay = REFRESH_MILLIS)
    fun refreshLocalSessions() {
        userRegistry.users.forEach { user ->
            val userId = userIdOf(user.principal) ?: return@forEach
            user.sessions.forEach { presence.markOnline(userId, it.id) }
        }
    }

    @Transactional
    fun touchLastActive(userId: Long) {
        profiles.findById(userId).ifPresent {
            it.lastActiveAt = clock.instant()
            profiles.save(it)
        }
    }

    private fun broadcastPresence(userId: Long, online: Boolean) {
        val matchIds = matches.findAllByUserAOrUserB(userId, userId).mapNotNull { it.id }
        if (matchIds.isEmpty()) return
        conversations.findAllByMatchIdIn(matchIds).forEach { convo ->
            relay.ifAvailable?.publish(
                ChatEvent(
                    type = "presence",
                    conversationId = requireNotNull(convo.id),
                    presenceUserId = userId,
                    online = online,
                ),
            )
        }
    }

    companion object {
        const val REFRESH_MILLIS = 120_000L // well inside the 5-min online window

        fun userIdOf(principal: Principal?): Long? =
            ((principal as? Authentication)?.principal as? KindredUserDetails)?.id
    }
}
