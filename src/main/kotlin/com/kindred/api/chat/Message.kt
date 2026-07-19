package com.kindred.api.chat

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

@Entity
@Table(name = "messages")
class Message(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "conversation_id", nullable = false)
    var conversationId: Long,

    @Column(name = "sender_id", nullable = false)
    var senderId: Long,

    @Column
    var body: String? = null,

    // chat media (§6B) lands with the private image pipeline
    @Column(name = "media_id")
    var mediaId: Long? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    @Column(name = "read_at")
    var readAt: Instant? = null,
)

interface MessageRepository : JpaRepository<Message, Long> {

    /** Newest-first keyset pagination: pass the oldest already-loaded id as `before`. */
    @Query(
        """
        SELECT m FROM Message m
        WHERE m.conversationId = :conversationId AND (:before IS NULL OR m.id < :before)
        ORDER BY m.id DESC
        """,
    )
    fun page(
        @Param("conversationId") conversationId: Long,
        @Param("before") before: Long?,
        pageable: Pageable,
    ): List<Message>

    fun findFirstByConversationIdOrderByIdDesc(conversationId: Long): Message?

    fun countByConversationIdAndSenderIdNotAndReadAtIsNull(conversationId: Long, senderId: Long): Long

    @Modifying
    @Query(
        """
        UPDATE Message m SET m.readAt = :now
        WHERE m.conversationId = :conversationId AND m.senderId <> :readerId AND m.readAt IS NULL
        """,
    )
    fun markRead(
        @Param("conversationId") conversationId: Long,
        @Param("readerId") readerId: Long,
        @Param("now") now: Instant,
    ): Int
}
