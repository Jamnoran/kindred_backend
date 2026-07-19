package com.kindred.api.profile

import com.kindred.api.discovery.MatchRepository
import com.kindred.api.discovery.PhotoSummary
import com.kindred.api.discovery.UserAgeLookup
import com.kindred.api.geo.CityIndex
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.PhotoRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.round

@Service
class ProfileService(
    private val profiles: ProfileRepository,
    private val interests: InterestRepository,
    private val cityIndex: CityIndex,
    private val clock: Clock,
    private val matches: MatchRepository,
    private val photos: PhotoRepository,
    private val userAges: UserAgeLookup,
    @param:Value("\${kindred.media.public-base-url}") private val publicBaseUrl: String,
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
        profile.gender = req.gender
        profile.lookingFor = req.lookingFor?.map { it.trim().lowercase() }?.distinct()
        profile.relationshipStyles = req.relationshipStyles?.let(RelationshipStyle::withUmbrella)
        profile.interests = resolvedInterests
        profile.lastActiveAt = clock.instant()
        return profiles.save(profile)
    }

    @Transactional
    fun updateLocation(userId: Long, req: UpdateLocationRequest): Profile {
        val lat = req.lat
        val lng = req.lng
        if ((lat == null) != (lng == null)) throw IncompleteCoordinatesException()
        val profile = profiles.findById(userId).orElseThrow { ProfileNotFoundException() }
        if (lat == null && !profile.locationSet) throw VisibilityWithoutLocationException()
        if (req.visibility != null) {
            profile.locationVisibility = req.visibility
        }
        if (lat != null && lng != null) {
            // Label from the *precise* coordinates (best nearest-city match), before snapping
            profile.locationLabel = cityIndex.nearest(lat, lng)?.name
        }
        profile.lastActiveAt = clock.instant()
        profiles.save(profile)
        if (lat != null && lng != null) {
            // Unless visibility is `exact`, snap to a ~5 km grid before storing so the DB
            // never holds precise coordinates for approximate/hidden users. Snapping only
            // happens on coordinate writes: a later visibility-only exact→approximate
            // switch keeps the already-stored coordinates (distances shown to others are
            // rounded regardless — see NearbyProfileResponse).
            val exact = profile.locationVisibility == LocationVisibility.exact
            val storedLat = if (exact) lat else snapToGrid(lat, SNAP_STEP_DEGREES)
            val storedLng = if (exact) lng else snapLngToGrid(lng, storedLat)
            // Native spatial write; flushes pending entity changes first, then clears the
            // persistence context so the re-read below sees location_set = 1
            profiles.updateLocation(userId, storedLat, storedLng)
        }
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

    /**
     * Returns the profile of a matched user. 404 if no match exists — by design,
     * the same response as a real missing profile (§ architecture: never reveal existence).
     */
    @Transactional(readOnly = true)
    fun getMatchProfile(requesterId: Long, targetId: Long): MatchProfileResponse {
        val userA = minOf(requesterId, targetId)
        val userB = maxOf(requesterId, targetId)
        if (!matches.existsByUserAAndUserB(userA, userB)) throw ProfileNotFoundException()
        val profile = profiles.findWithInterestsByUserId(targetId) ?: throw ProfileNotFoundException()
        val photo = photos.findAllByProfileUserIdInAndModerationStatusAndIsPrimaryTrue(
            listOf(targetId), ModerationStatus.approved,
        ).firstOrNull()
        return MatchProfileResponse(
            userId = targetId,
            displayName = profile.displayName,
            age = userAges.ageOf(targetId),
            bio = profile.bio,
            gender = profile.gender,
            lookingFor = profile.lookingFor ?: emptyList(),
            relationshipStyles = profile.relationshipStyles ?: emptyList(),
            interests = profile.interests.map { it.slug }.sorted(),
            photo = PhotoSummary.from(photo, publicBaseUrl),
        )
    }

    @Transactional(readOnly = true)
    fun listInterests(): List<Interest> = interests.findAllByOrderByLabelAsc()

    private companion object {
        /** ~5 km of latitude, the same coarseness as the displayed-distance rounding. */
        const val SNAP_STEP_DEGREES = 0.045
    }

    private fun snapToGrid(value: Double, step: Double): Double = round(value / step) * step

    /** Longitude degrees shrink with latitude — widen the step so grid cells stay ~5 km. */
    private fun snapLngToGrid(lng: Double, atLat: Double): Double {
        val step = SNAP_STEP_DEGREES / max(cos(Math.toRadians(atLat)), 0.1)
        return snapToGrid(lng, step).coerceIn(-180.0, 180.0)
    }

    private fun resolveInterests(slugs: List<String>): MutableSet<Interest> {
        if (slugs.isEmpty()) return mutableSetOf()
        val normalized = slugs.map { it.trim().lowercase() }.distinct()
        val found = interests.findBySlugIn(normalized)
        val foundSlugs = found.map { it.slug }.toSet()
        normalized.firstOrNull { it !in foundSlugs }?.let { throw UnknownInterestException(it) }
        return found.toMutableSet()
    }
}
