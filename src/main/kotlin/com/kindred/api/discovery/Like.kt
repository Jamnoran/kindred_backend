package com.kindred.api.discovery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.time.Instant

/** Lowercase constants to match the MySQL ENUM values. */
@Suppress("EnumEntryName")
enum class LikeKind { like, superlike, pass }

data class LikeId(var fromUser: Long = 0, var toUser: Long = 0) : Serializable

@Entity
@Table(name = "likes")
@IdClass(LikeId::class)
class Like(
    @Id
    @Column(name = "from_user")
    var fromUser: Long,

    @Id
    @Column(name = "to_user")
    var toUser: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var kind: LikeKind,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

interface LikeRepository : JpaRepository<Like, LikeId> {

    fun findByFromUserAndToUser(fromUser: Long, toUser: Long): Like?

    /** "Who liked you" — free, no gating (§7): likes I haven't reacted to yet. */
    @Query(
        """
        SELECT l FROM Like l
        WHERE l.toUser = :userId AND l.kind <> :pass
          AND NOT EXISTS (SELECT 1 FROM Like r WHERE r.fromUser = :userId AND r.toUser = l.fromUser)
        ORDER BY l.createdAt DESC
        """,
    )
    fun findUnansweredReceived(@Param("userId") userId: Long, @Param("pass") pass: LikeKind = LikeKind.pass): List<Like>
}
