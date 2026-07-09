package com.kindred.api.notification

import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmailNotificationChannelTest {

    private val mailer: NotificationMailer = mock()
    private val channel = EmailNotificationChannel(mailer, "http://web.test")

    private fun notification(type: NotificationType) = OfflineNotification(
        type = type,
        recipientUserId = 2L,
        recipientEmail = "bea@example.com",
        otherDisplayName = "Alice",
        conversationId = 7L,
    )

    @Test
    fun `match emails name the other person and deep-link the conversation`() {
        channel.send(notification(NotificationType.new_match))

        val subject = argumentCaptor<String>()
        val body = argumentCaptor<String>()
        verify(mailer).send(eq("bea@example.com"), subject.capture(), body.capture())
        assertEquals("You have a new match on Kindred", subject.firstValue)
        assertTrue("Alice" in body.firstValue)
        assertTrue("http://web.test/conversations/7" in body.firstValue)
    }

    @Test
    fun `message emails say who wrote but never leak content`() {
        channel.send(notification(NotificationType.new_message))

        val subject = argumentCaptor<String>()
        val body = argumentCaptor<String>()
        verify(mailer).send(eq("bea@example.com"), subject.capture(), body.capture())
        assertEquals("Alice sent you a message on Kindred", subject.firstValue)
        assertTrue("http://web.test/conversations/7" in body.firstValue)
    }
}
