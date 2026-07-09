package com.kindred.api.discovery

import com.kindred.api.profile.RelationshipStyle
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The transparent scoring function (ARCHITECTURE.md §7) — a pure function so every
 * factor it reports is exactly what produced the ordering. No hidden inputs.
 *
 *   score = (w_i·interests + w_d·proximity + w_a·activity + w_m·mutualFit) / Σw
 */
object DiscoveryScoring {

    data class Weights(
        val interests: Double = 1.0,
        val distance: Double = 1.0,
        val activity: Double = 1.0,
        val mutualFit: Double = 1.0,
    ) {
        companion object {
            val KEYS = setOf("interests", "distance", "activity", "mutualFit")

            fun from(map: Map<String, Double>?): Weights {
                fun w(key: String) = (map?.get(key) ?: 1.0).coerceIn(0.0, 5.0)
                return Weights(w("interests"), w("distance"), w("activity"), w("mutualFit"))
            }
        }
    }

    data class Factors(
        val sharedInterests: List<String>,
        val interestScore: Double,
        val distanceKm: Int?,
        val distanceScore: Double,
        val daysSinceActive: Long,
        val activityScore: Double,
        val mutualFitScore: Double,
        val weights: Weights,
        val total: Double,
    )

    fun score(
        viewerInterests: Set<String>,
        candidateInterests: Set<String>,
        distanceMeters: Double?,
        maxDistanceKm: Int,
        lastActiveAt: Instant,
        now: Instant,
        viewerAge: Int,
        candidateAgeMin: Int?,
        candidateAgeMax: Int?,
        viewerLookingFor: Set<String>,
        candidateLookingFor: Set<String>,
        viewerStyles: Set<RelationshipStyle> = emptySet(),
        candidateStyles: Set<RelationshipStyle> = emptySet(),
        weights: Weights,
    ): Factors {
        val shared = viewerInterests.intersect(candidateInterests).sorted()
        val union = viewerInterests.union(candidateInterests)
        val interestScore = if (union.isEmpty()) 0.0 else shared.size.toDouble() / union.size

        val distanceKm = distanceMeters?.let { (it / 1000.0).roundToInt() }
        val distanceScore = if (distanceMeters == null) {
            0.5 // unknown location — neutral, not penalised
        } else {
            (1.0 - (distanceMeters / 1000.0) / maxOf(1, maxDistanceKm)).coerceIn(0.0, 1.0)
        }

        val daysSinceActive = Duration.between(lastActiveAt, now).toDays().coerceAtLeast(0)
        val activityScore = when {
            daysSinceActive < 1 -> 1.0
            daysSinceActive < 7 -> 0.7
            daysSinceActive < 30 -> 0.4
            else -> 0.1
        }

        // Does the viewer fit what the candidate says they want, and do their
        // looking_for / relationship-style declarations overlap? Unknown → neutral
        // 0.5, never a penalty.
        val ageFit = if (candidateAgeMin == null || candidateAgeMax == null) {
            0.5
        } else if (viewerAge in candidateAgeMin..candidateAgeMax) 1.0 else 0.0
        val lookingForFit = if (viewerLookingFor.isEmpty() || candidateLookingFor.isEmpty()) {
            0.5
        } else if (viewerLookingFor.intersect(candidateLookingFor).isNotEmpty()) 1.0 else 0.0
        val styleFit = if (viewerStyles.isEmpty() || candidateStyles.isEmpty()) {
            0.5
        } else if (viewerStyles.intersect(candidateStyles).isNotEmpty()) 1.0 else 0.0
        val mutualFitScore = ((ageFit + lookingForFit + styleFit) / 3).round3()

        val weightSum = weights.interests + weights.distance + weights.activity + weights.mutualFit
        val total = if (weightSum == 0.0) {
            0.0
        } else {
            (
                weights.interests * interestScore +
                    weights.distance * distanceScore +
                    weights.activity * activityScore +
                    weights.mutualFit * mutualFitScore
                ) / weightSum
        }

        return Factors(
            sharedInterests = shared,
            interestScore = interestScore.round3(),
            distanceKm = distanceKm,
            distanceScore = distanceScore.round3(),
            daysSinceActive = daysSinceActive,
            activityScore = activityScore,
            mutualFitScore = mutualFitScore,
            weights = weights,
            total = total.round3(),
        )
    }

    private fun Double.round3(): Double = (this * 1000).roundToInt() / 1000.0
}
