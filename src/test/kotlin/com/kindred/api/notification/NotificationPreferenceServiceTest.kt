package com.kindred.api.notification

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationPreferenceServiceTest {

    private val repo: NotificationPreferenceRepository = mock()
    private val service = NotificationPreferenceService(repo)

    @Test
    fun `with nothing stored everything is enabled`() {
        val response = service.preferences(1L)

        assertEquals(NotificationType.entries.size * NotificationChannelType.entries.size, response.preferences.size)
        assertTrue(response.preferences.all { it.enabled })
    }

    @Test
    fun `stored overrides show up in the grid with defaults for the rest`() {
        whenever(repo.findAllByUserId(1L)).thenReturn(
            listOf(
                NotificationPreference(
                    userId = 1L,
                    notificationType = NotificationType.new_message,
                    channel = NotificationChannelType.email,
                    enabled = false,
                ),
            ),
        )

        val byType = service.preferences(1L).preferences.associateBy { it.type to it.channel }

        assertEquals(false, byType[NotificationType.new_message to NotificationChannelType.email]?.enabled)
        assertEquals(true, byType[NotificationType.new_match to NotificationChannelType.email]?.enabled)
    }

    @Test
    fun `replace wipes the old rows and stores the new ones`() {
        val entries = listOf(
            NotificationPreferenceEntry(NotificationType.new_message, NotificationChannelType.email, enabled = false),
        )
        whenever(repo.saveAll(any<List<NotificationPreference>>())).thenAnswer { it.arguments[0] }

        val response = service.replace(1L, entries)

        verify(repo).deleteAllByUserId(1L)
        val byType = response.preferences.associateBy { it.type to it.channel }
        assertEquals(false, byType[NotificationType.new_message to NotificationChannelType.email]?.enabled)
        // combos missing from the request reset to the default
        assertEquals(true, byType[NotificationType.new_match to NotificationChannelType.email]?.enabled)
    }

    @Test
    fun `a repeated type-channel pair is rejected before anything is written`() {
        val entries = listOf(
            NotificationPreferenceEntry(NotificationType.new_match, NotificationChannelType.email, enabled = false),
            NotificationPreferenceEntry(NotificationType.new_match, NotificationChannelType.email, enabled = true),
        )

        assertThrows<DuplicateNotificationPreferenceException> { service.replace(1L, entries) }
        verify(repo, never()).deleteAllByUserId(any())
    }

    @Test
    fun `enabledChannels honours stored opt-outs and defaults the rest on`() {
        whenever(repo.findAllByUserIdAndNotificationType(1L, NotificationType.new_message)).thenReturn(
            listOf(
                NotificationPreference(
                    userId = 1L,
                    notificationType = NotificationType.new_message,
                    channel = NotificationChannelType.email,
                    enabled = false,
                ),
            ),
        )

        assertEquals(emptySet(), service.enabledChannels(1L, NotificationType.new_message))
        assertEquals(setOf(NotificationChannelType.email), service.enabledChannels(1L, NotificationType.new_match))
    }
}
