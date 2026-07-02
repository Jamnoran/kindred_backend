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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "looking_for")
    var lookingFor: List<String>? = null,

    // Set to true only by ProfileRepository.updateLocation (entities are re-read after it)
    @Column(name = "location_set", nullable = false)
    var locationSet: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "location_visibility", nullable = false)
    var locationVisibility: LocationVisibility = LocationVisibility.approximate,

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
