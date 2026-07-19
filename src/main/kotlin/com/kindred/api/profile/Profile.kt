package com.kindred.api.profile

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** Lowercase constants to match the MySQL ENUM values (and the JSON wire format). */
@Suppress("EnumEntryName")
enum class LocationVisibility { exact, approximate, hidden }

/**
 * Optional, self-identified (null = prefer not to say). Deliberately no separate
 * trans categories — trans women select `woman`, etc. Orientation is never stored
 * as a label; it is expressed as `Preferences.genders` and enforced mutually.
 */
@Suppress("EnumEntryName")
enum class Gender { woman, man, nonbinary }

/**
 * Fixed relationship-style vocabulary (JSON wire values). `non_monogamy` is the
 * umbrella term for any ethical non-monogamy; profile declarations of `open` or
 * `polyamory` get it added automatically (see [withUmbrella]) so a `non_monogamy`
 * preference filter matches every ENM profile. Multi-select: declaring both
 * `monogamy` and `non_monogamy` means "open to either".
 */
@Suppress("EnumEntryName")
enum class RelationshipStyle {
    monogamy, non_monogamy, open, polyamory;

    companion object {
        /**
         * Umbrella expansion for *profile declarations only* — never applied to
         * preference filters, where `polyamory` must keep meaning "specifically poly".
         */
        fun withUmbrella(styles: List<RelationshipStyle>): List<RelationshipStyle> =
            if (styles.any { it == open || it == polyamory }) (styles + non_monogamy).distinct()
            else styles.distinct()
    }
}

/**
 * The `location POINT` column is deliberately not mapped: it is written only via
 * ProfileRepository.updateLocation (native `ST_SRID(POINT(...))`) and read only by
 * spatial queries, so no hibernate-spatial dependency is needed. Inserts rely on the
 * column's DB default of POINT(0,0) with location_set = 0.
 */
@Entity
@Table(name = "profiles")
class Profile(
    @Id
    @Column(name = "user_id")
    var userId: Long,

    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,

    @Column
    var bio: String? = null,

    @Enumerated(EnumType.STRING)
    @Column
    var gender: Gender? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "looking_for")
    var lookingFor: List<String>? = null,

    /** Always stored umbrella-normalized (RelationshipStyle.withUmbrella). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "relationship_styles")
    var relationshipStyles: List<RelationshipStyle>? = null,

    // Set to true only by ProfileRepository.updateLocation (entities are re-read after it)
    @Column(name = "location_set", nullable = false)
    var locationSet: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "location_visibility", nullable = false)
    var locationVisibility: LocationVisibility = LocationVisibility.approximate,

    /** Nearest-city label for the stored location — always city-coarse, safe to display. */
    @Column(name = "location_label", length = 120)
    var locationLabel: String? = null,

    @Column(name = "last_active_at", nullable = false)
    var lastActiveAt: Instant = Instant.now(),

    @ManyToMany
    @JoinTable(
        name = "profile_interests",
        joinColumns = [JoinColumn(name = "profile_user_id")],
        inverseJoinColumns = [JoinColumn(name = "interest_id")],
    )
    var interests: MutableSet<Interest> = mutableSetOf(),
)
