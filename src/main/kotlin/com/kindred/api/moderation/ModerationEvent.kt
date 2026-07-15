package com.kindred.api.moderation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * Append-only audit log of moderation actions (ARCHITECTURE.md §9). Actions so far:
 * report_filed, report_dismissed, user_banned, user_unbanned, user_deleted.
 */
@Entity
@Table(name = "moderation_events")
class ModerationEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // NULL = automated pipeline action
    @Column(name = "actor_user_id")
    var actorUserId: Long? = null,

    @Column(name = "subject_user_id")
    var subjectUserId: Long? = null,

    @Column(nullable = false, length = 64)
    var action: String,

    @Column(name = "target_type", length = 32)
    var targetType: String? = null,

    @Column(name = "target_id")
    var targetId: Long? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    var detail: Map<String, Any?>? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

interface ModerationEventRepository : JpaRepository<ModerationEvent, Long>
