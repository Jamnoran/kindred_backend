package com.kindred.api.discovery

import com.kindred.api.chat.Conversation
import com.kindred.api.chat.ConversationRepository
import com.kindred.api.notification.NotificationService
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LikeServiceTest {

    private val likes: LikeRepository = mock()
    private val matches: MatchRepository = mock()
    private val conversations: ConversationRepository = mock()
    private val profiles: ProfileRepository = mock()
    private val photos: PhotoRepository = mock()
    private val notifications: NotificationService = mock()
    private val clock = Clock.fixed(Instant.parse("2026-07-02T12:00:00Z"), ZoneOffset.UTC)
    private val service = LikeService(likes, matches, conversations, profiles, photos, notifications, clock, "http://cdn.test")

    @Test
    fun `a one-sided like does not match`() {
        whenever(profiles.existsById(2L)).thenReturn(true)
        whenever(likes.findByFromUserAndToUser(1L, 2L)).thenReturn(null)
        whenever(likes.findByFromUserAndToUser(2L, 1L)).thenReturn(null)
        whenever(likes.save(any())).thenAnswer { it.arguments[0] }

        val result = service.react(1L, 2L, LikeKind.like)

        assertFalse(result.matched)
        verify(matches, never()).save(any())
        verify(notifications, never()).matchCreated(any(), any(), any())
    }

    @Test
    fun `a mutual like creates the match and its conversation`() {
        whenever(profiles.existsById(1L)).thenReturn(true)
        whenever(likes.findByFromUserAndToUser(2L, 1L)).thenReturn(null)
        whenever(likes.findByFromUserAndToUser(1L, 2L))
            .thenReturn(Like(fromUser = 1L, toUser = 2L, kind = LikeKind.superlike))
        whenever(likes.save(any())).thenAnswer { it.arguments[0] }
        whenever(matches.existsByUserAAndUserB(1L, 2L)).thenReturn(false)
        whenever(matches.save(any())).thenAnswer { (it.arguments[0] as Match).apply { id = 9L } }
        whenever(conversations.save(any())).thenAnswer { (it.arguments[0] as Conversation).apply { id = 5L } }

        // user 2 likes back user 1 — note ordered pair (1,2) regardless of direction
        val result = service.react(2L, 1L, LikeKind.like)

        assertTrue(result.matched)
        assertEquals(9L, result.matchId)
        assertEquals(5L, result.conversationId)
        // the reactor (user 2) saw the match in the response; user 1 gets the offline notification
        verify(notifications).matchCreated(eq(2L), any(), eq(5L))
    }

    @Test
    fun `a pass never matches even against an existing like`() {
        whenever(profiles.existsById(1L)).thenReturn(true)
        whenever(likes.findByFromUserAndToUser(2L, 1L)).thenReturn(null)
        whenever(likes.save(any())).thenAnswer { it.arguments[0] }

        val result = service.react(2L, 1L, LikeKind.pass)

        assertFalse(result.matched)
        verify(likes, never()).findByFromUserAndToUser(1L, 2L)
        verify(matches, never()).save(any())
    }

    @Test
    fun `a like answered by an earlier pass does not match`() {
        whenever(profiles.existsById(1L)).thenReturn(true)
        whenever(likes.findByFromUserAndToUser(2L, 1L)).thenReturn(null)
        whenever(likes.findByFromUserAndToUser(1L, 2L))
            .thenReturn(Like(fromUser = 1L, toUser = 2L, kind = LikeKind.pass))
        whenever(likes.save(any())).thenAnswer { it.arguments[0] }

        assertFalse(service.react(2L, 1L, LikeKind.like).matched)
        verify(matches, never()).save(any())
    }

    @Test
    fun `guards - self, missing target, duplicate reaction`() {
        assertThrows<CannotReactToSelfException> { service.react(1L, 1L, LikeKind.like) }

        whenever(profiles.existsById(2L)).thenReturn(false)
        assertThrows<ReactionTargetNotFoundException> { service.react(1L, 2L, LikeKind.like) }

        whenever(profiles.existsById(2L)).thenReturn(true)
        whenever(likes.findByFromUserAndToUser(1L, 2L))
            .thenReturn(Like(fromUser = 1L, toUser = 2L, kind = LikeKind.like))
        assertThrows<AlreadyReactedException> { service.react(1L, 2L, LikeKind.like) }
    }

    @Test
    fun `an existing match is not duplicated`() {
        whenever(profiles.existsById(1L)).thenReturn(true)
        whenever(likes.findByFromUserAndToUser(2L, 1L)).thenReturn(null)
        whenever(likes.findByFromUserAndToUser(1L, 2L))
            .thenReturn(Like(fromUser = 1L, toUser = 2L, kind = LikeKind.like))
        whenever(likes.save(any())).thenAnswer { it.arguments[0] }
        whenever(matches.existsByUserAAndUserB(1L, 2L)).thenReturn(true)

        assertFalse(service.react(2L, 1L, LikeKind.like).matched)
        verify(matches, never()).save(any())
    }
}
