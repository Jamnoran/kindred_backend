package com.kindred.api.notification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/** A stored per-user choice; combos without a row use the default: enabled. */
@Entity
@Table(name = "notification_preferences")
class NotificationPreference(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 32)
    var notificationType: NotificationType,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    var channel: NotificationChannelType,

    @Column(nullable = false)
    var enabled: Boolean = true,
)

interface NotificationPreferenceRepository : JpaRepository<NotificationPreference, Long> {
    fun findAllByUserId(userId: Long): List<NotificationPreference>
    fun findAllByUserIdAndNotificationType(userId: Long, type: NotificationType): List<NotificationPreference>
    fun deleteAllByUserId(userId: Long)
}
