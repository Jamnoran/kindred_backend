package com.kindred.api.discovery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** Stored ordered (user_a < user_b) to dedupe — enforced by a DB CHECK constraint. */
@Entity
@Table(name = "matches")
class Match(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "user_a", nullable = false)
    var userA: Long,

    @Column(name = "user_b", nullable = false)
    var userB: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
) {
    fun involves(userId: Long): Boolean = userA == userId || userB == userId
    fun otherThan(userId: Long): Long = if (userA == userId) userB else userA
}

interface MatchRepository : JpaRepository<Match, Long> {
    fun existsByUserAAndUserB(userA: Long, userB: Long): Boolean
    fun findAllByUserAOrUserB(userA: Long, userB: Long): List<Match>
}
