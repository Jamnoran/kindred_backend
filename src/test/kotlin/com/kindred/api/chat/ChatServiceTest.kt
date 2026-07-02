package com.kindred.api.chat

import com.kindred.api.discovery.Match
import com.kindred.api.discovery.MatchRepository
import com.kindred.api.photo.PhotoRepository
import com.kindred.api.profile.ProfileRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals

class ChatServiceTest {

    private val conversations: ConversationRepository = mock()
    private val messages: MessageRepository = mock()
    private val matches: MatchRepository = mock()
    private val profiles: ProfileRepository = mock()
    private val photos: PhotoRepository = mock()
    private val messaging: org.springframework.beans.factory.ObjectProvider<org.springframework.messaging.simp.SimpMessagingTemplate> = mock()
    private val relay: org.springframework.beans.factory.ObjectProvider<ChatEventPublisher> = mock()
    private val chatMedia: ChatMediaService = mock()
    private val now = Instant.parse("2026-07-02T12:00:00Z")
    private val service = ChatService(
        conversations, messages, matches, profiles, photos,
        ConversationAccess(conversations, matches), chatMedia, PresenceTracker(),
        Clock.fixed(now, ZoneOffset.UTC), messaging, relay, "http://cdn.test",
    )

    private fun stubConversation(convoId: Long = 7L, matchId: Long = 3L, userA: Long = 1L, userB: Long = 2L) {
        whenever(conversations.findById(convoId)).thenReturn(Optional.of(Conversation(id = convoId, matchId = matchId)))
        whenever(matches.findById(matchId)).thenReturn(Optional.of(Match(id = matchId, userA = userA, userB = userB)))
    }

    @Test
    fun `members can send and the body is trimmed`() {
        stubConversation()
        whenever(messages.save(any())).thenAnswer { (it.arguments[0] as Message).apply { id = 100L } }

        val sent = service.send(1L, 7L, "  hello there  ")

        assertEquals("hello there", sent.body)
        assertEquals(1L, sent.senderId)
        assertEquals(now, sent.createdAt)
    }

    @Test
    fun `a message needs a body or a media key`() {
        stubConversation()

        assertThrows<EmptyMessageException> { service.send(1L, 7L, "   ", null) }
        verify(messages, never()).save(any())
    }

    @Test
    fun `an image-only message attaches media through the pipeline`() {
        stubConversation()
        val key = "chat-quarantine/" + "ab".repeat(16)
        whenever(chatMedia.attach(1L, 7L, key)).thenReturn(
            Media(id = 30L, storageKey = key, ownerUserId = 1L, conversationId = 7L),
        )
        whenever(messages.save(any())).thenAnswer { (it.arguments[0] as Message).apply { id = 101L } }

        val sent = service.send(1L, 7L, null, key)

        assertEquals(30L, sent.mediaId)
        assertEquals(null, sent.body)
    }

    @Test
    fun `non-members get not-found on every operation - no probing`() {
        stubConversation(userA = 1L, userB = 2L)

        assertThrows<ConversationNotFoundException> { service.send(99L, 7L, "hi") }
        assertThrows<ConversationNotFoundException> { service.messages(99L, 7L, null, 50) }
        assertThrows<ConversationNotFoundException> { service.markRead(99L, 7L) }
        verify(messages, never()).save(any())
    }

    @Test
    fun `unknown conversations are not-found`() {
        whenever(conversations.findById(404L)).thenReturn(Optional.empty())

        assertThrows<ConversationNotFoundException> { service.send(1L, 404L, "hi") }
    }

    @Test
    fun `markRead only touches the other side's unread messages`() {
        stubConversation()
        whenever(messages.markRead(7L, 1L, now)).thenReturn(3)

        assertEquals(3, service.markRead(1L, 7L))
        verify(messages).markRead(eq(7L), eq(1L), eq(now))
    }

    @Test
    fun `conversation list includes the other participant and unread count`() {
        val match = Match(id = 3L, userA = 1L, userB = 2L, createdAt = now)
        whenever(matches.findAllByUserAOrUserB(1L, 1L)).thenReturn(listOf(match))
        whenever(conversations.findAllByMatchIdIn(setOf(3L))).thenReturn(listOf(Conversation(id = 7L, matchId = 3L)))
        whenever(profiles.findAllById(listOf(2L))).thenReturn(
            listOf(com.kindred.api.profile.Profile(userId = 2L, displayName = "Bea")),
        )
        whenever(photos.findAllByProfileUserIdInAndModerationStatusAndIsPrimaryTrue(any(), any())).thenReturn(emptyList())
        whenever(messages.findFirstByConversationIdOrderByIdDesc(7L))
            .thenReturn(Message(id = 50L, conversationId = 7L, senderId = 2L, body = "hey", createdAt = now))
        whenever(messages.countByConversationIdAndSenderIdNotAndReadAtIsNull(7L, 1L)).thenReturn(1L)

        val list = service.listConversations(1L)

        assertEquals(1, list.size)
        assertEquals(2L, list[0].otherUser.userId)
        assertEquals("Bea", list[0].otherUser.displayName)
        assertEquals("hey", list[0].lastMessage?.body)
        assertEquals(1L, list[0].unreadCount)
    }
}
