package com.kindred.api.profile

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ProfileService(
    private val profiles: ProfileRepository,
    private val interests: InterestRepository,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun getOwn(userId: Long): Profile =
        profiles.findWithInterestsByUserId(userId) ?: throw ProfileNotFoundException()

    /** Create-or-update of the caller's own profile. PUT semantics: omitted fields are cleared. */
    @Transactional
    fun upsert(userId: Long, req: UpdateProfileRequest): Profile {
        val resolvedInterests = resolveInterests(req.interests ?: emptyList())
        val profile = profiles.findWithInterestsByUserId(userId)
            ?: Profile(userId = userId, displayName = req.displayName)
        profile.displayName = req.displayName
        profile.bio = req.bio?.takeIf { it.isNotBlank() }
        profile.lookingFor = req.lookingFor?.map { it.trim().lowercase() }?.distinct()
        profile.interests = resolvedInterests
        profile.lastActiveAt = clock.instant()
        return profiles.save(profile)
    }

    @Transactional
    fun updateLocation(userId: Long, req: UpdateLocationRequest): Profile {
        val profile = profiles.findById(userId).orElseThrow { ProfileNotFoundException() }
        if (req.visibility != null) {
            profile.locationVisibility = req.visibility
        }
        profile.lastActiveAt = clock.instant()
        profiles.save(profile)
        // Native spatial write; flushes pending entity changes first, then clears the
        // persistence context so the re-read below sees location_set = 1
        profiles.updateLocation(userId, req.lat, req.lng)
        return profiles.findWithInterestsByUserId(userId) ?: throw ProfileNotFoundException()
    }

    @Transactional(readOnly = true)
    fun nearby(userId: Long, radiusKm: Int): List<NearbyProfileView> {
        val own = profiles.findById(userId).orElseThrow { ProfileNotFoundException() }
        if (!own.locationSet) {
            throw LocationNotSetException()
        }
        return profiles.findNearby(userId, radiusKm * 1000.0)
    }

    @Transactional(readOnly = true)
    fun listInterests(): List<Interest> = interests.findAllByOrderByLabelAsc()

    private fun resolveInterests(slugs: List<String>): MutableSet<Interest> {
        if (slugs.isEmpty()) return mutableSetOf()
        val normalized = slugs.map { it.trim().lowercase() }.distinct()
        val found = interests.findBySlugIn(normalized)
        val foundSlugs = found.map { it.slug }.toSet()
        normalized.firstOrNull { it !in foundSlugs }?.let { throw UnknownInterestException(it) }
        return found.toMutableSet()
    }
}
