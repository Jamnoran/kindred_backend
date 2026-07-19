package com.kindred.api.chat

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** One conversation per match, created transactionally when the match forms. */
@Entity
@Table(name = "conversations")
class Conversation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "match_id", nullable = false)
    var matchId: Long,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

interface ConversationRepository : JpaRepository<Conversation, Long> {
    fun findByMatchId(matchId: Long): Conversation?
    fun findAllByMatchIdIn(matchIds: Collection<Long>): List<Conversation>
}
