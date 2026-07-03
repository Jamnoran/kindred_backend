package com.kindred.api.chat

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.discovery.Match
import com.kindred.api.discovery.MatchRepository
import com.kindred.api.profile.Profile
import com.kindred.api.profile.ProfileRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessageType
import org.springframework.messaging.simp.user.SimpSession
import org.springframework.messaging.simp.user.SimpUser
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.messaging.support.MessageBuilder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.messaging.SessionConnectedEvent
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import java.security.Principal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals

class PresenceTrackerTest {

    private val presence: PresenceService = mock()
    private val userRegistry: SimpUserRegistry = mock()
    private val profiles: ProfileRepository = mock()
    private val matches: MatchRepository = mock()
    private val conversations: ConversationRepository = mock()
    private val relayInstance: ChatEventRelay = mock()
    private val relay: ObjectProvider<ChatEventRelay> = mock {
        on { ifAvailable } doReturn relayInstance
    }
    private val now = Instant.parse("2026-07-03T12:00:00Z")
    private val tracker = PresenceTracker(
        presence, userRegistry, profiles, matches, conversations, relay, Clock.fixed(now, ZoneOffset.UTC),
    )

    private fun principal(userId: Long): Principal = UsernamePasswordAuthenticationToken(
        KindredUserDetails(id = userId, email = "u$userId@example.com", passwordHash = "h", emailVerified = true),
        null,
        emptyList(),
    )

    private fun connectedEvent(userId: Long, sessionId: String): SessionConnectedEvent {
        val accessor = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK)
        accessor.sessionId = sessionId
        return SessionConnectedEvent(this, MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders), principal(userId))
    }

    private fun disconnectEvent(userId: Long, sessionId: String): SessionDisconnectEvent {
        val accessor = SimpMessageHeaderAccessor.create(SimpMessageType.DISCONNECT)
        accessor.sessionId = sessionId
        return SessionDisconnectEvent(
            this, MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders),
            sessionId, CloseStatus.NORMAL, principal(userId),
        )
    }

    @Test
    fun `first connect marks online, touches last-active, and broadcasts to the user's conversations`() {
        whenever(presence.isOnline(1L)).thenReturn(false)
        val profile = Profile(userId = 1L, displayName = "Al", lastActiveAt = now.minusSeconds(9999))
        whenever(profiles.findById(1L)).thenReturn(Optional.of(profile))
        whenever(matches.findAllByUserAOrUserB(1L, 1L)).thenReturn(listOf(Match(id = 3L, userA = 1L, userB = 2L)))
        whenever(conversations.findAllByMatchIdIn(listOf(3L))).thenReturn(listOf(Conversation(id = 7L, matchId = 3L)))

        tracker.onConnected(connectedEvent(1L, "ws-1"))

        verify(presence).markOnline(1L, "ws-1")
        assertEquals(now, profile.lastActiveAt)
        verify(relayInstance).publish(
            ChatEvent(type = "presence", conversationId = 7L, presenceUserId = 1L, online = true),
        )
    }

    @Test
    fun `a second session does not re-broadcast`() {
        whenever(presence.isOnline(1L)).thenReturn(true)
        whenever(profiles.findById(1L)).thenReturn(Optional.empty())

        tracker.onConnected(connectedEvent(1L, "ws-2"))

        verify(presence).markOnline(1L, "ws-2")
        verify(relayInstance, never()).publish(any())
    }

    @Test
    fun `disconnect broadcasts offline only when the last session is gone`() {
        whenever(matches.findAllByUserAOrUserB(1L, 1L)).thenReturn(listOf(Match(id = 3L, userA = 1L, userB = 2L)))
        whenever(conversations.findAllByMatchIdIn(listOf(3L))).thenReturn(listOf(Conversation(id = 7L, matchId = 3L)))

        whenever(presence.isOnline(1L)).thenReturn(true) // another session remains
        tracker.onDisconnect(disconnectEvent(1L, "ws-1"))
        verify(relayInstance, never()).publish(any())

        whenever(presence.isOnline(1L)).thenReturn(false)
        tracker.onDisconnect(disconnectEvent(1L, "ws-2"))
        verify(presence).markOffline(1L, "ws-2")
        verify(relayInstance).publish(
            ChatEvent(type = "presence", conversationId = 7L, presenceUserId = 1L, online = false),
        )
    }

    @Test
    fun `the sweep re-registers every locally connected session`() {
        val session: SimpSession = mock { on { id } doReturn "ws-9" }
        val user: SimpUser = mock {
            on { getPrincipal() } doReturn principal(5L)
            on { sessions } doReturn setOf(session)
        }
        whenever(userRegistry.users).thenReturn(setOf(user))

        tracker.refreshLocalSessions()

        verify(presence).markOnline(5L, "ws-9")
    }

    @Test
    fun `events without a kindred principal are ignored`() {
        val accessor = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK)
        accessor.sessionId = "ws-x"
        val event = SessionConnectedEvent(this, MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders), null)

        tracker.onConnected(event)

        verify(presence, never()).markOnline(any(), any())
    }
}
