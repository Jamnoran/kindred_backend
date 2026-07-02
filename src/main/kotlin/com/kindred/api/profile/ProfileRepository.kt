package com.kindred.api.profile

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

/** Projection for the nearby spatial query. */
interface NearbyProfileView {
    val userId: Long
    val displayName: String
    val distanceMeters: Double
}

interface ProfileRepository : JpaRepository<Profile, Long> {

    /** Interests are lazy and mapped to DTOs outside the transaction — fetch them eagerly here. */
    @EntityGraph(attributePaths = ["interests"])
    fun findWithInterestsByUserId(userId: Long): Profile?

    /** Spatial write — POINT is lng/lat order in MySQL, SRID 4326 to match the column. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = "UPDATE profiles SET location = ST_SRID(POINT(:lng, :lat), 4326), location_set = 1 WHERE user_id = :userId",
        nativeQuery = true,
    )
    fun updateLocation(userId: Long, lat: Double, lng: Double)

    /**
     * Profiles within radiusMeters of the caller's own stored location, nearest first,
     * using ST_Distance_Sphere against the SPATIAL index (ARCHITECTURE.md §5). Hidden
     * locations never appear. Empty when the caller hasn't set a location.
     */
    @Query(
        value = """
            SELECT p.user_id AS userId,
                   p.display_name AS displayName,
                   ST_Distance_Sphere(p.location, me.location) AS distanceMeters
            FROM profiles p
            JOIN profiles me ON me.user_id = :userId AND me.location_set = 1
            WHERE p.user_id <> :userId
              AND p.location_set = 1
              AND p.location_visibility <> 'hidden'
              AND ST_Distance_Sphere(p.location, me.location) <= :radiusMeters
            ORDER BY distanceMeters
            LIMIT 100
        """,
        nativeQuery = true,
    )
    fun findNearby(userId: Long, radiusMeters: Double): List<NearbyProfileView>
}
