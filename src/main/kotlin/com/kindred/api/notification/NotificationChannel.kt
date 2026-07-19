package com.kindred.api.notification

/**
 * Everything a channel needs to render an offline notification. Message content
 * deliberately does not travel here: notifications reveal *that* something happened,
 * never what was said (email/push providers store payloads outside our control).
 */
data class OfflineNotification(
    val type: NotificationType,
    val recipientUserId: Long,
    val recipientEmail: String,
    val otherDisplayName: String,
    val conversationId: Long,
)

/**
 * One delivery mechanism (email today; push/sms later). Implementations are Spring
 * beans; NotificationService fans out to every bean whose type the recipient has
 * enabled, so adding a channel = implement this + extend NotificationChannelType.
 * Throwing lets the JobRunr job retry the whole notification.
 */
interface NotificationChannel {
    val channelType: NotificationChannelType
    fun send(notification: OfflineNotification)
}
