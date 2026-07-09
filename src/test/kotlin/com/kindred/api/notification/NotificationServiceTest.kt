package com.kindred.api.notification

import com.kindred.api.auth.User
import com.kindred.api.auth.UserRepository
import com.kindred.api.chat.MessageRepository
import com.kindred.api.chat.PresenceService
import com.kindred.api.discovery.Match
import com.kindred.api.profile.Profile
import com.kindred.api.profile.ProfileRepository
import org.jobrunr.scheduling.JobRequestScheduler
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

class NotificationServiceTest {

    private val users: UserRepository = mock()
    private val profiles: ProfileRepository = mock()
    private val messages: MessageRepository = mock()
    private val preferences: NotificationPreferenceService = mock()
    private val emailChannel: NotificationChannel = mock {
        on { channelType } doReturn NotificationChannelType.email
    }
    private val jobs: JobRequestScheduler = mock()
    private val presenceInstance: PresenceService = mock()
    private val presence: ObjectProvider<PresenceService> = mock {
        on { ifAvailable } doReturn presenceInstance
    }
    private val throttleInstance: NotificationThrottle = mock()
    private val throttle: ObjectProvider<NotificationThrottle> = mock {
        on { ifAvailable } doReturn throttleInstance
    }
    private val service = NotificationService(
        users, profiles, messages, preferences, listOf(emailChannel), jobs, presence, throttle,
    )

    private val messageRequest = SendNotificationRequest(
        type = NotificationType.new_message, recipientUserId = 2L, otherUserId = 1L, conversationId = 7L,
    )
    private val matchRequest = SendNotificationRequest(
        type = NotificationType.new_match, recipientUserId = 2L, otherUserId = 1L, conversationId = 7L,
    )

    private fun stubRecipient(deleted: Boolean = false) {
        whenever(users.findById(2L)).thenReturn(
            Optional.of(
                User(
                    id = 2L, email = "bea@example.com", passwordHash = "h", emailVerified = true,
                    dob = LocalDate.parse("1990-01-01"),
                    deletedAt = if (deleted) Instant.parse("2026-07-08T00:00:00Z") else null,
                ),
            ),
        )
    }

    private fun stubSender() {
        whenever(profiles.findById(1L)).thenReturn(Optional.of(Profile(userId = 1L, displayName = "Alice")))
    }

    private fun stubUnread(count: Long = 1L) {
        whenever(messages.countByConversationIdAndSenderIdNotAndReadAtIsNull(7L, 2L)).thenReturn(count)
    }

    private fun stubEmailEnabled(type: NotificationType) {
        whenever(preferences.enabledChannels(2L, type)).thenReturn(setOf(NotificationChannelType.email))
    }

    @Test
    fun `a match enqueues a notification for the participant who did not just react`() {
        service.matchCreated(reactorId = 2L, match = Match(id = 3L, userA = 1L, userB = 2L), conversationId = 7L)

        verify(jobs).enqueue(
            SendNotificationRequest(
                type = NotificationType.new_match, recipientUserId = 1L, otherUserId = 2L, conversationId = 7L,
            ),
        )
    }

    @Test
    fun `a message enqueues a notification for the recipient`() {
        service.messageSent(senderId = 1L, recipientId = 2L, conversationId = 7L)

        verify(jobs).enqueue(messageRequest)
    }

    @Test
    fun `an offline recipient with unread messages gets the notification and starts the cooldown`() {
        stubRecipient()
        stubSender()
        stubUnread()
        stubEmailEnabled(NotificationType.new_message)

        service.dispatch(messageRequest)

        verify(emailChannel).send(
            OfflineNotification(
                type = NotificationType.new_message,
                recipientUserId = 2L,
                recipientEmail = "bea@example.com",
                otherDisplayName = "Alice",
                conversationId = 7L,
            ),
        )
        verify(throttleInstance).markNotified(2L, 7L)
    }

    @Test
    fun `an online recipient is never notified`() {
        whenever(presenceInstance.isOnline(2L)).thenReturn(true)

        service.dispatch(messageRequest)
        service.dispatch(matchRequest)

        verify(emailChannel, never()).send(any())
    }

    @Test
    fun `messages already read by dispatch time are skipped`() {
        stubRecipient()
        stubSender()
        stubUnread(0L)
        stubEmailEnabled(NotificationType.new_message)

        service.dispatch(messageRequest)

        verify(emailChannel, never()).send(any())
        verify(throttleInstance, never()).markNotified(any(), any())
    }

    @Test
    fun `the per-conversation cooldown swallows message bursts`() {
        stubRecipient()
        stubSender()
        stubUnread()
        stubEmailEnabled(NotificationType.new_message)
        whenever(throttleInstance.isThrottled(2L, 7L)).thenReturn(true)

        service.dispatch(messageRequest)

        verify(emailChannel, never()).send(any())
    }

    @Test
    fun `match notifications ignore the message cooldown`() {
        stubRecipient()
        stubSender()
        stubEmailEnabled(NotificationType.new_match)
        whenever(throttleInstance.isThrottled(2L, 7L)).thenReturn(true)

        service.dispatch(matchRequest)

        verify(emailChannel).send(any())
        verify(throttleInstance, never()).markNotified(any(), any())
    }

    @Test
    fun `a channel the user disabled is skipped and no cooldown starts`() {
        stubRecipient()
        stubSender()
        stubUnread()
        whenever(preferences.enabledChannels(2L, NotificationType.new_message)).thenReturn(emptySet())

        service.dispatch(messageRequest)

        verify(emailChannel, never()).send(any())
        verify(throttleInstance, never()).markNotified(any(), any())
    }

    @Test
    fun `deleted or vanished recipients are skipped`() {
        stubSender()
        stubUnread()
        stubEmailEnabled(NotificationType.new_message)

        whenever(users.findById(2L)).thenReturn(Optional.empty())
        service.dispatch(messageRequest)

        stubRecipient(deleted = true)
        service.dispatch(messageRequest)

        verify(emailChannel, never()).send(any())
    }

    @Test
    fun `absent presence reads as offline so the notification still goes out`() {
        val noPresence: ObjectProvider<PresenceService> = mock { on { ifAvailable } doReturn null }
        val noThrottle: ObjectProvider<NotificationThrottle> = mock { on { ifAvailable } doReturn null }
        val svc = NotificationService(
            users, profiles, messages, preferences, listOf(emailChannel), jobs, noPresence, noThrottle,
        )
        stubRecipient()
        stubSender()
        stubUnread()
        stubEmailEnabled(NotificationType.new_message)

        svc.dispatch(messageRequest)

        verify(emailChannel).send(any())
    }
}
