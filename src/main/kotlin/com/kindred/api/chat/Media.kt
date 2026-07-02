package com.kindred.api.chat

import com.kindred.api.photo.ModerationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * Chat images (§6B): private bucket prefix, never public. `storage_key` holds the
 * chat-quarantine key while pending and the final `chat-media/<hex>` key once the
 * pipeline approves it. Served exclusively via 5-minute signed URLs to participants.
 */
@Entity
@Table(name = "media")
class Media(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "storage_key", nullable = false)
    var storageKey: String,

    @Column(name = "owner_user_id", nullable = false)
    var ownerUserId: Long,

    @Column(name = "conversation_id", nullable = false)
    var conversationId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false)
    var moderationStatus: ModerationStatus = ModerationStatus.pending,

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

interface MediaRepository : JpaRepository<Media, Long> {
    fun existsByStorageKey(storageKey: String): Boolean
}
