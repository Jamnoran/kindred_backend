package com.kindred.api.discovery

import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.Photo
import com.kindred.api.photo.PhotoRepository
import com.kindred.api.profile.LocationVisibility
import com.kindred.api.profile.Profile
import com.kindred.api.profile.ProfileNotFoundException
import com.kindred.api.profile.ProfileRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class DiscoveryService(
    private val candidates: CandidateRepository,
    private val profiles: ProfileRepository,
    private val photos: PhotoRepository,
    private val preferencesService: PreferencesService,
    private val preferencesRepository: PreferencesRepository,
    private val userAges: UserAgeLookup,
    private val clock: Clock,
    @param:Value("\${kindred.media.public-base-url}") private val publicBaseUrl: String,
) {

    /**
     * §7 discovery: SQL hard filters → JSON hard filters (looking_for, dealbreakers)
     * → transparent scoring → sort. Every card carries its full score breakdown.
     */
    @Transactional(readOnly = true)
    fun discover(viewerId: Long, limit: Int): List<DiscoveryCard> {
        val viewerProfile = profiles.findWithInterestsByUserId(viewerId) ?: throw ProfileNotFoundException()
        val prefs = preferencesService.get(viewerId)
        val weights = DiscoveryScoring.Weights.from(prefs.weights)
        val viewerAge = userAges.ageOf(viewerId)
        val viewerInterests = viewerProfile.interests.map { it.slug }.toSet()
        val viewerGender = viewerProfile.gender
        val viewerLookingFor = (viewerProfile.lookingFor ?: emptyList()).toSet()
        val viewerStyles = (viewerProfile.relationshipStyles ?: emptyList()).toSet()
        val genderPrefs = (prefs.genders ?: emptyList()).toSet()
        val prefsLookingFor = (prefs.lookingFor ?: emptyList()).toSet()
        val prefsStyles = (prefs.relationshipStyles ?: emptyList()).toSet()
        val dealbreakers = (prefs.dealbreakers ?: emptyList()).toSet()

        val rows = candidates.findCandidates(viewerId, prefs.ageMin, prefs.ageMax, prefs.distanceKm * 1000.0)
        if (rows.isEmpty()) return emptyList()

        val ids = rows.map { it.userId }
        val profilesById = profiles.findAllWithInterestsByUserIdIn(ids).associateBy { it.userId }
        val candidatePrefs = preferencesRepository.findAllById(ids).associateBy { it.userId }
        val primaryPhotos = photos.findAllByProfileUserIdInAndModerationStatusAndIsPrimaryTrue(ids, ModerationStatus.approved)
            .associateBy(Photo::profileUserId)
        val now = clock.instant()

        return rows.mapNotNull { row ->
            val profile = profilesById[row.userId] ?: return@mapNotNull null
            val cPrefs = candidatePrefs[row.userId]
            val candidateInterests = profile.interests.map { it.slug }.toSet()
            // hard filters the SQL can't see (JSON columns)
            if (dealbreakers.isNotEmpty() && candidateInterests.any { it in dealbreakers }) return@mapNotNull null
            // Gender is the one MUTUAL hard filter: you never appear to someone your
            // "show me" excludes, and vice versa. A set filter also excludes profiles
            // with no gender declared (null is never in a non-empty set).
            if (genderPrefs.isNotEmpty() && profile.gender !in genderPrefs) return@mapNotNull null
            val candidateGenderPrefs = (cPrefs?.genders ?: emptyList()).toSet()
            if (candidateGenderPrefs.isNotEmpty() && viewerGender !in candidateGenderPrefs) return@mapNotNull null
            val candidateLookingFor = (profile.lookingFor ?: emptyList()).toSet()
            if (prefsLookingFor.isNotEmpty() && candidateLookingFor.isNotEmpty() &&
                prefsLookingFor.intersect(candidateLookingFor).isEmpty()
            ) {
                return@mapNotNull null
            }
            // Relationship styles behave like looking_for: only filters candidates
            // who declared styles (profiles are stored umbrella-normalized, so a
            // `non_monogamy` filter matches open/poly declarations too).
            val candidateStyles = (profile.relationshipStyles ?: emptyList()).toSet()
            if (prefsStyles.isNotEmpty() && candidateStyles.isNotEmpty() &&
                prefsStyles.intersect(candidateStyles).isEmpty()
            ) {
                return@mapNotNull null
            }

            val factors = DiscoveryScoring.score(
                viewerInterests = viewerInterests,
                candidateInterests = candidateInterests,
                distanceMeters = row.distanceMeters,
                maxDistanceKm = prefs.distanceKm,
                lastActiveAt = row.lastActiveAt.toInstant(),
                now = now,
                viewerAge = viewerAge,
                candidateAgeMin = cPrefs?.ageMin,
                candidateAgeMax = cPrefs?.ageMax,
                viewerLookingFor = viewerLookingFor,
                candidateLookingFor = candidateLookingFor,
                viewerStyles = viewerStyles,
                candidateStyles = candidateStyles,
                weights = weights,
            )
            DiscoveryCard(
                userId = row.userId,
                displayName = profile.displayName,
                age = row.age,
                bio = profile.bio,
                gender = profile.gender,
                lookingFor = candidateLookingFor.sorted(),
                relationshipStyles = candidateStyles.sorted(),
                interests = candidateInterests.sorted(),
                photo = PhotoSummary.from(primaryPhotos[row.userId], publicBaseUrl),
                distanceKm = displayDistanceKm(profile, factors.distanceKm),
                score = factors.total,
                whyThisPerson = factors,
            )
        }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** Respect location_visibility in what we *show* (scoring still uses the real value). */
    private fun displayDistanceKm(candidate: Profile, distanceKm: Int?): Int? = when {
        distanceKm == null -> null
        candidate.locationVisibility == LocationVisibility.hidden -> null
        candidate.locationVisibility == LocationVisibility.approximate ->
            (((distanceKm + 4) / 5) * 5).coerceAtLeast(5) // rounded up to 5 km steps
        else -> distanceKm
    }
}
