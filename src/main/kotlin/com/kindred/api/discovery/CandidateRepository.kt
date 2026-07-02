package com.kindred.api.discovery

import com.kindred.api.profile.Profile
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.sql.Timestamp

interface CandidateView {
    val userId: Long
    val age: Int
    val distanceMeters: Double?
    val lastActiveAt: Timestamp
}

/**
 * The SQL half of discovery: hard filters that never let a candidate through
 * (§7 — age window, viewer's distance limit via ST_Distance_Sphere, no repeats,
 * blocks severed both ways, deleted/unverified users invisible). JSON-based
 * filters (looking_for, dealbreakers) are applied in DiscoveryService.
 */
interface CandidateRepository : Repository<Profile, Long> {

    @Query(
        value = """
            SELECT p.user_id AS userId,
                   TIMESTAMPDIFF(YEAR, u.dob, CURDATE()) AS age,
                   CASE WHEN me.location_set = 1 AND p.location_set = 1
                        THEN ST_Distance_Sphere(p.location, me.location) END AS distanceMeters,
                   p.last_active_at AS lastActiveAt
            FROM profiles p
            JOIN users u ON u.id = p.user_id
            JOIN profiles me ON me.user_id = :viewerId
            WHERE p.user_id <> :viewerId
              AND u.deleted_at IS NULL
              AND u.email_verified = 1
              AND TIMESTAMPDIFF(YEAR, u.dob, CURDATE()) BETWEEN :ageMin AND :ageMax
              AND NOT EXISTS (
                    SELECT 1 FROM likes l WHERE l.from_user = :viewerId AND l.to_user = p.user_id)
              AND NOT EXISTS (
                    SELECT 1 FROM blocks b
                    WHERE (b.blocker_id = :viewerId AND b.blocked_id = p.user_id)
                       OR (b.blocker_id = p.user_id AND b.blocked_id = :viewerId))
              AND (me.location_set = 0 OR p.location_set = 0
                   OR ST_Distance_Sphere(p.location, me.location) <= :maxDistanceMeters)
            ORDER BY p.last_active_at DESC
            LIMIT 200
        """,
        nativeQuery = true,
    )
    fun findCandidates(
        @Param("viewerId") viewerId: Long,
        @Param("ageMin") ageMin: Int,
        @Param("ageMax") ageMax: Int,
        @Param("maxDistanceMeters") maxDistanceMeters: Double,
    ): List<CandidateView>
}
