package com.kindred.api.discovery

import com.kindred.api.profile.Gender
import com.kindred.api.profile.RelationshipStyle
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository

/**
 * User-controlled hard filters + transparent-matching weights (ARCHITECTURE.md §7).
 * Candidates outside the hard filters never appear; weights only reorder.
 */
@Entity
@Table(name = "preferences")
class Preferences(
    @Id
    @Column(name = "user_id")
    var userId: Long,

    @Column(name = "distance_km", nullable = false)
    var distanceKm: Int = 50,

    @Column(name = "age_min", nullable = false)
    var ageMin: Int = 18,

    @Column(name = "age_max", nullable = false)
    var ageMax: Int = 99,

    /**
     * "Show me" genders; empty/NULL = everyone. Enforced MUTUALLY in discovery:
     * neither side sees the other unless both filters accept (a set filter also
     * hides profiles with no gender declared, in both directions).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    var genders: List<Gender>? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "looking_for")
    var lookingFor: List<String>? = null,

    /**
     * Candidate must declare at least one of these styles (candidates who declared
     * nothing still pass, like looking_for). Stored verbatim — no umbrella expansion.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "relationship_styles")
    var relationshipStyles: List<RelationshipStyle>? = null,

    /** Interest slugs the candidate must NOT have — enforced as a hard filter. */
    @JdbcTypeCode(SqlTypes.JSON)
    var dealbreakers: List<String>? = null,

    /** Scoring weights (interests/distance/activity/mutualFit), each 0..5. */
    @JdbcTypeCode(SqlTypes.JSON)
    var weights: Map<String, Double>? = null,
)

interface PreferencesRepository : JpaRepository<Preferences, Long>
