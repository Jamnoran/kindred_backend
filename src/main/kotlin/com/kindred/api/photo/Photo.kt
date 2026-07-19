package com.kindred.api.photo

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

/** Lowercase constants to match the MySQL ENUM values. */
@Suppress("EnumEntryName")
enum class ModerationStatus { pending, approved, rejected }

/**
 * `storage_key` holds the quarantine key while pending, and the final
 * `profiles/<hex>` base key (with /thumb.jpg, /card.jpg, /full.jpg objects under it)
 * once processing approves the photo.
 */
@Entity
@Table(name = "photos")
class Photo(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "profile_user_id", nullable = false)
    var profileUserId: Long,

    @Column(name = "storage_key", nullable = false)
    var storageKey: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "is_primary", nullable = false)
    var isPrimary: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false)
    var moderationStatus: ModerationStatus = ModerationStatus.pending,

    @Column(length = 64)
    var blurhash: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

interface PhotoRepository : JpaRepository<Photo, Long> {
    fun countByProfileUserId(profileUserId: Long): Long
    fun findAllByProfileUserIdOrderBySortOrderAsc(profileUserId: Long): List<Photo>
    fun findByIdAndProfileUserId(id: Long, profileUserId: Long): Photo?
    fun existsByStorageKey(storageKey: String): Boolean
    fun findAllByProfileUserIdInAndModerationStatusAndIsPrimaryTrue(
        profileUserIds: Collection<Long>,
        moderationStatus: ModerationStatus,
    ): List<Photo>
}
