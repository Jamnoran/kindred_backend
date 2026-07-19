package com.kindred.api.premium

import com.kindred.api.auth.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Premium is a one-time paid upgrade (no subscription, never expires) that unlocks
 * extra features. First one: image messaging in chat — enabled for a conversation
 * when *either* participant is premium, so one upgrade unlocks the chat for both.
 */
@Service
class PremiumService(
    private val users: UserRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun isPremium(userId: Long): Boolean = users.findPremiumIds(listOf(userId)).isNotEmpty()

    /** Which of [userIds] are premium — one query, for hydrating lists. */
    @Transactional(readOnly = true)
    fun premiumIdsOf(userIds: Collection<Long>): Set<Long> =
        if (userIds.isEmpty()) emptySet() else users.findPremiumIds(userIds)

    @Transactional(readOnly = true)
    fun anyPremium(userIds: Collection<Long>): Boolean = premiumIdsOf(userIds).isNotEmpty()

    @Transactional(readOnly = true)
    fun status(userId: Long): PremiumStatusResponse {
        val since = users.findById(userId).orElseThrow().premiumSince
        return PremiumStatusResponse(premium = since != null, premiumSince = since)
    }

    /**
     * Marks the one-time purchase as completed. Idempotent — a retried payment
     * webhook keeps the original purchase time. There is deliberately no public
     * endpoint for this: only the payment provider's verified completion callback
     * may call it.
     */
    @Transactional
    fun grant(userId: Long): PremiumStatusResponse {
        val user = users.findById(userId).orElseThrow()
        if (user.premiumSince == null) {
            user.premiumSince = clock.instant()
            users.save(user)
        }
        return PremiumStatusResponse(premium = true, premiumSince = user.premiumSince)
    }
}
