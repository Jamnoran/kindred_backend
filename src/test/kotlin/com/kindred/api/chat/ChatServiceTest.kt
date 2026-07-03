package com.kindred.api.chat

import com.kindred.api.discovery.Match
import com.kindred.api.discovery.MatchRepository
import com.kindred.api.photo.InvalidStorageKeyException
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.PhotoRepository
import com.kindred.api.profile.ProfileRepository
import org.jobrunr.scheduling.JobRequestScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatServiceTest {

    private val conversations: ConversationRepository = mock()
    private val messages: MessageRepository = mock()
    private val matches: MatchRepository = mock()
    private val profiles: ProfileRepository = mock()
    private val photos: PhotoRepository = mock()
    private val chatMedia: ChatMediaRepository = mock()
    private val jobs: JobRequestScheduler = mock()
    private val relayInstance: ChatEventRelay = mock()
    private val relay: org.springframework.beans.factory.ObjectProvider<ChatEventRelay> = mock {
        on { ifAvailable } doReturn relayInstance
    }
    private val presenceInstance: PresenceService = mock()
    private val presence: org.springframework.beans.factory.ObjectProvider<PresenceService> = mock {
        on { ifAvailable } doReturn presenceInstance
    }
    private val now = Instant.parse("2026-07-02T12:00:00Z")
    private val service = ChatService(
        conversations, messages, matches, profiles, photos, chatMedia, jobs,
        Clock.fixed(now, ZoneOffset.UTC), relay, presence, "http://cdn.test",
    )

    private val validMediaKey = "quarantine/" + "ef".repeat(16)

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
        verify(relayInstance).publish(ChatEvent(type = "message", conversationId = 7L, message = sent))
    }

    @Test
    fun `sending an image records pending media and enqueues processing`() {
        stubConversation()
        whenever(chatMedia.save(any())).thenAnswer { (it.arguments[0] as ChatMedia).apply { id = 30L } }
        whenever(messages.save(any())).thenAnswer { (it.arguments[0] as Message).apply { id = 100L } }

        val sent = service.send(1L, 7L, body = null, mediaStorageKey = validMediaKey)

        assertNull(sent.body)
        assertEquals(30L, sent.media?.id)
        assertEquals(ModerationStatus.pending, sent.media?.status)
        verify(jobs).enqueue(ProcessChatMediaRequest(30L))
        verify(relayInstance).publish(ChatEvent(type = "message", conversationId = 7L, message = sent))
    }

    @Test
    fun `messages need a body or an image`() {
        stubConversation()

        assertThrows<EmptyMessageException> { service.send(1L, 7L, "   ") }
        assertThrows<EmptyMessageException> { service.send(1L, 7L, null) }
        verify(messages, never()).save(any())
    }

    @Test
    fun `media storage keys are validated and single-use`() {
        stubConversation()

        assertThrows<InvalidStorageKeyException> { service.send(1L, 7L, null, "chat-media/abcd") }
        assertThrows<InvalidStorageKeyException> { service.send(1L, 7L, null, "quarantine/../etc/passwd") }

        whenever(chatMedia.existsByStorageKey(validMediaKey)).thenReturn(true)
        assertThrows<InvalidStorageKeyException> { service.send(1L, 7L, null, validMediaKey) }
        verify(messages, never()).save(any())
    }

    @Test
    fun `a key already used for a profile photo is rejected`() {
        stubConversation()
        whenever(photos.existsByStorageKey(validMediaKey)).thenReturn(true)

        assertThrows<InvalidStorageKeyException> { service.send(1L, 7L, null, validMediaKey) }
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
    fun `message pages hydrate attached media`() {
        stubConversation()
        val withMedia = Message(id = 51L, conversationId = 7L, senderId = 2L, mediaId = 30L, createdAt = now)
        whenever(messages.page(eq(7L), eq(null), any())).thenReturn(listOf(withMedia))
        whenever(chatMedia.findAllById(listOf(30L))).thenReturn(
            listOf(ChatMedia(id = 30L, storageKey = "chat-media/aa", ownerUserId = 2L, conversationId = 7L, moderationStatus = ModerationStatus.approved, blurhash = "LKO2")),
        )

        val page = service.messages(1L, 7L, null, 50)

        assertEquals(ModerationStatus.approved, page[0].media?.status)
        assertEquals("LKO2", page[0].media?.blurhash)
    }

    @Test
    fun `conversation list includes the other participant, unread count, and presence`() {
        val match = Match(id = 3L, userA = 1L, userB = 2L, createdAt = now)
        whenever(matches.findAllByUserAOrUserB(1L, 1L)).thenReturn(listOf(match))
        whenever(conversations.findAllByMatchIdIn(setOf(3L))).thenReturn(listOf(Conversation(id = 7L, matchId = 3L)))
        whenever(profiles.findAllById(listOf(2L))).thenReturn(
            listOf(com.kindred.api.profile.Profile(userId = 2L, displayName = "Bea")),
        )
        whenever(photos.findAllByProfileUserIdInAndModerationStatusAndIsPrimaryTrue(any(), any())).thenReturn(emptyList())
        whenever(presenceInstance.onlineOf(listOf(2L))).thenReturn(setOf(2L))
        whenever(messages.findFirstByConversationIdOrderByIdDesc(7L))
            .thenReturn(Message(id = 50L, conversationId = 7L, senderId = 2L, body = "hey", createdAt = now))
        whenever(messages.countByConversationIdAndSenderIdNotAndReadAtIsNull(7L, 1L)).thenReturn(1L)

        val list = service.listConversations(1L)

        assertEquals(1, list.size)
        assertEquals(2L, list[0].otherUser.userId)
        assertEquals("Bea", list[0].otherUser.displayName)
        assertTrue(list[0].otherUser.online)
        assertEquals("hey", list[0].lastMessage?.body)
        assertEquals(1L, list[0].unreadCount)
    }

    @Test
    fun `everyone reads as offline when presence is unavailable`() {
        val offlinePresence: org.springframework.beans.factory.ObjectProvider<PresenceService> = mock {
            on { ifAvailable } doReturn null
        }
        val svc = ChatService(
            conversations, messages, matches, profiles, photos, chatMedia, jobs,
            Clock.fixed(now, ZoneOffset.UTC), relay, offlinePresence, "http://cdn.test",
        )
        val match = Match(id = 3L, userA = 1L, userB = 2L, createdAt = now)
        whenever(matches.findAllByUserAOrUserB(1L, 1L)).thenReturn(listOf(match))
        whenever(conversations.findAllByMatchIdIn(setOf(3L))).thenReturn(listOf(Conversation(id = 7L, matchId = 3L)))
        whenever(profiles.findAllById(listOf(2L))).thenReturn(
            listOf(com.kindred.api.profile.Profile(userId = 2L, displayName = "Bea")),
        )
        whenever(photos.findAllByProfileUserIdInAndModerationStatusAndIsPrimaryTrue(any(), any())).thenReturn(emptyList())

        assertFalse(svc.listConversations(1L)[0].otherUser.online)
    }
}
