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
 * A chat image (§6B — the `media` table). Unlike profile photos these are never
 * public: `storage_key` holds the quarantine key while pending and the private
 * `chat-media/<hex>` base key once approved, and bytes are only ever served through
 * short-lived signed URLs to the two conversation participants.
 */
@Entity
@Table(name = "media")
class ChatMedia(
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

    /** Adult-but-legal (§9): approved for chat, but clients blur it until the viewer opts in. */
    @Column(name = "is_nsfw", nullable = false)
    var isNsfw: Boolean = false,

    @Column(length = 64)
    var blurhash: String? = null,

    @Column(name = "expires_at")
    var expiresAt: Instant? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

interface ChatMediaRepository : JpaRepository<ChatMedia, Long> {
    fun existsByStorageKey(storageKey: String): Boolean
}
