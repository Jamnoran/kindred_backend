package com.kindred.api.notification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Sends notification emails. Swap for real SMTP before launch (same deal as VerificationMailer). */
interface NotificationMailer {
    fun send(to: String, subject: String, body: String)
}

/** Dev/self-host default: logs instead of sending, so the flow is testable without SMTP. */
@Component
class LoggingNotificationMailer : NotificationMailer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(to: String, subject: String, body: String) {
        log.info("Notification email to {}: [{}] {}", to, subject, body)
    }
}

@Component
class EmailNotificationChannel(
    private val mailer: NotificationMailer,
    @param:Value("\${kindred.web-base-url:http://localhost:3000}") private val webBaseUrl: String,
) : NotificationChannel {

    override val channelType = NotificationChannelType.email

    override fun send(notification: OfflineNotification) {
        val link = "$webBaseUrl/conversations/${notification.conversationId}"
        when (notification.type) {
            NotificationType.new_match -> mailer.send(
                notification.recipientEmail,
                "You have a new match on Kindred",
                "You and ${notification.otherDisplayName} liked each other. Say hi: $link",
            )
            NotificationType.new_message -> mailer.send(
                notification.recipientEmail,
                "${notification.otherDisplayName} sent you a message on Kindred",
                "Read it here: $link",
            )
        }
    }
}
