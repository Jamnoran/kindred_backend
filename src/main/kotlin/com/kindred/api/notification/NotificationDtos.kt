package com.kindred.api.notification

/** Lowercase constants are the wire format and the DB strings (same style as LikeKind). */
@Suppress("EnumEntryName")
enum class NotificationType { new_match, new_message }

/** Delivery channels a user can toggle. Extend here (push, sms, …) as channels land. */
@Suppress("EnumEntryName")
enum class NotificationChannelType { email }

data class NotificationPreferenceEntry(
    val type: NotificationType,
    val channel: NotificationChannelType,
    val enabled: Boolean,
)

/** Always the complete type × channel grid, defaults filled in — clients round-trip this shape. */
data class NotificationPreferencesResponse(val preferences: List<NotificationPreferenceEntry>)

data class UpdateNotificationPreferencesRequest(val preferences: List<NotificationPreferenceEntry>)

class DuplicateNotificationPreferenceException :
    RuntimeException("preferences must not repeat a type/channel pair")
