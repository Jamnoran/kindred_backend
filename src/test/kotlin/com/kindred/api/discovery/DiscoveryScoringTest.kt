package com.kindred.api.discovery

import com.kindred.api.profile.RelationshipStyle
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoveryScoringTest {

    private val now = Instant.parse("2026-07-02T12:00:00Z")

    private fun score(
        viewerInterests: Set<String> = setOf("hiking", "coffee"),
        candidateInterests: Set<String> = setOf("hiking", "coffee"),
        distanceMeters: Double? = 0.0,
        maxDistanceKm: Int = 50,
        lastActiveAt: Instant = now,
        viewerAge: Int = 30,
        candidateAgeMin: Int? = 18,
        candidateAgeMax: Int? = 99,
        viewerLookingFor: Set<String> = setOf("dating"),
        candidateLookingFor: Set<String> = setOf("dating"),
        viewerStyles: Set<RelationshipStyle> = setOf(RelationshipStyle.monogamy),
        candidateStyles: Set<RelationshipStyle> = setOf(RelationshipStyle.monogamy),
        weights: DiscoveryScoring.Weights = DiscoveryScoring.Weights(),
    ) = DiscoveryScoring.score(
        viewerInterests, candidateInterests, distanceMeters, maxDistanceKm, lastActiveAt, now,
        viewerAge, candidateAgeMin, candidateAgeMax, viewerLookingFor, candidateLookingFor,
        viewerStyles, candidateStyles, weights,
    )

    @Test
    fun `perfect candidate scores 1`() {
        val f = score()
        assertEquals(1.0, f.total)
        assertEquals(listOf("coffee", "hiking"), f.sharedInterests)
        assertEquals(1.0, f.interestScore)
        assertEquals(1.0, f.distanceScore)
        assertEquals(1.0, f.activityScore)
        assertEquals(1.0, f.mutualFitScore)
    }

    @Test
    fun `interest score is the jaccard ratio of shared interests`() {
        val f = score(
            viewerInterests = setOf("hiking", "coffee", "art"),
            candidateInterests = setOf("hiking", "gaming"),
        )
        assertEquals(listOf("hiking"), f.sharedInterests)
        assertEquals(0.25, f.interestScore) // 1 shared / 4 union
    }

    @Test
    fun `distance decays linearly to the viewer's own max`() {
        assertEquals(0.5, score(distanceMeters = 25_000.0, maxDistanceKm = 50).distanceScore)
        assertEquals(0.0, score(distanceMeters = 50_000.0, maxDistanceKm = 50).distanceScore)
        assertEquals(25, score(distanceMeters = 25_000.0).distanceKm)
    }

    @Test
    fun `unknown location is neutral, never a penalty`() {
        val f = score(distanceMeters = null)
        assertEquals(0.5, f.distanceScore)
        assertEquals(null, f.distanceKm)
    }

    @Test
    fun `activity score steps down with inactivity`() {
        assertEquals(1.0, score(lastActiveAt = now.minusSeconds(3600)).activityScore)
        assertEquals(0.7, score(lastActiveAt = now.minusSeconds(3 * 86_400)).activityScore)
        assertEquals(0.4, score(lastActiveAt = now.minusSeconds(10 * 86_400)).activityScore)
        assertEquals(0.1, score(lastActiveAt = now.minusSeconds(100 * 86_400)).activityScore)
    }

    @Test
    fun `mutual fit drops when the viewer is outside the candidate's age window`() {
        val f = score(viewerAge = 45, candidateAgeMin = 20, candidateAgeMax = 35)
        assertEquals(0.667, f.mutualFitScore) // age 0 + lookingFor 1 + style 1, averaged
    }

    @Test
    fun `undeclared preferences are neutral`() {
        val f = score(
            candidateAgeMin = null,
            candidateAgeMax = null,
            candidateLookingFor = emptySet(),
            candidateStyles = emptySet(),
        )
        assertEquals(0.5, f.mutualFitScore)
    }

    @Test
    fun `mismatched relationship styles drop mutual fit, umbrella declarations overlap`() {
        val monoVsPoly = score(
            viewerStyles = setOf(RelationshipStyle.monogamy),
            candidateStyles = setOf(RelationshipStyle.polyamory, RelationshipStyle.non_monogamy),
        )
        assertEquals(0.667, monoVsPoly.mutualFitScore) // age 1 + lookingFor 1 + style 0

        val enmVsOpen = score(
            viewerStyles = setOf(RelationshipStyle.non_monogamy),
            // profiles store `open` umbrella-normalized to include non_monogamy
            candidateStyles = setOf(RelationshipStyle.open, RelationshipStyle.non_monogamy),
        )
        assertEquals(1.0, enmVsOpen.mutualFitScore)
    }

    @Test
    fun `weights change the ordering and are reported back`() {
        // candidate A: shares interests, far away; candidate B: nothing shared, next door
        val a = score(candidateInterests = setOf("hiking", "coffee"), distanceMeters = 45_000.0)
        val b = score(candidateInterests = setOf("gaming"), distanceMeters = 1_000.0)
        assertTrue(a.total > b.total) // equal weights: interests tie-break

        val distanceHeavy = DiscoveryScoring.Weights.from(mapOf("distance" to 5.0, "interests" to 0.0))
        val a2 = score(candidateInterests = setOf("hiking", "coffee"), distanceMeters = 45_000.0, weights = distanceHeavy)
        val b2 = score(candidateInterests = setOf("gaming"), distanceMeters = 1_000.0, weights = distanceHeavy)
        assertTrue(b2.total > a2.total)
        assertEquals(5.0, a2.weights.distance)
    }

    @Test
    fun `weights are clamped and default to 1`() {
        val w = DiscoveryScoring.Weights.from(mapOf("interests" to 99.0, "activity" to -3.0))
        assertEquals(5.0, w.interests)
        assertEquals(0.0, w.activity)
        assertEquals(1.0, w.distance)
        assertEquals(DiscoveryScoring.Weights(), DiscoveryScoring.Weights.from(null))
    }

    @Test
    fun `all-zero weights produce zero, not division by zero`() {
        val f = score(weights = DiscoveryScoring.Weights(0.0, 0.0, 0.0, 0.0))
        assertEquals(0.0, f.total)
    }
}
