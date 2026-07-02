package com.kindred.api.chat

import com.kindred.api.discovery.MatchRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * AuthZ on every read and send (§8): non-membership is indistinguishable from
 * nonexistence, so conversation ids can't be probed.
 */
@Component
class ConversationAccess(
    private val conversations: ConversationRepository,
    private val matches: MatchRepository,
) {

    @Transactional(readOnly = true)
    fun requireMembership(userId: Long, conversationId: Long) {
        val convo = conversations.findById(conversationId).orElseThrow { ConversationNotFoundException() }
        val match = matches.findById(convo.matchId).orElseThrow { ConversationNotFoundException() }
        if (!match.involves(userId)) throw ConversationNotFoundException()
    }
}
