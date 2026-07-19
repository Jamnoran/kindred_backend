package com.kindred.api.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationPreferenceService(
    private val prefs: NotificationPreferenceRepository,
) {

    @Transactional(readOnly = true)
    fun preferences(userId: Long): NotificationPreferencesResponse = matrix(prefs.findAllByUserId(userId))

    /** Full replace (PUT semantics): combos missing from [entries] reset to the default (enabled). */
    @Transactional
    fun replace(userId: Long, entries: List<NotificationPreferenceEntry>): NotificationPreferencesResponse {
        if (entries.distinctBy { it.type to it.channel }.size != entries.size) {
            throw DuplicateNotificationPreferenceException()
        }
        prefs.deleteAllByUserId(userId)
        val saved = prefs.saveAll(
            entries.map {
                NotificationPreference(userId = userId, notificationType = it.type, channel = it.channel, enabled = it.enabled)
            },
        )
        return matrix(saved)
    }

    /** Channels the user has (or defaults to) enabled for [type] — the dispatch-side lookup. */
    @Transactional(readOnly = true)
    fun enabledChannels(userId: Long, type: NotificationType): Set<NotificationChannelType> {
        val overrides = prefs.findAllByUserIdAndNotificationType(userId, type).associateBy { it.channel }
        return NotificationChannelType.entries.filterTo(mutableSetOf()) { overrides[it]?.enabled ?: true }
    }

    private fun matrix(rows: List<NotificationPreference>): NotificationPreferencesResponse {
        val byKey = rows.associateBy { it.notificationType to it.channel }
        return NotificationPreferencesResponse(
            NotificationType.entries.flatMap { type ->
                NotificationChannelType.entries.map { channel ->
                    NotificationPreferenceEntry(type, channel, byKey[type to channel]?.enabled ?: true)
                }
            },
        )
    }
}
