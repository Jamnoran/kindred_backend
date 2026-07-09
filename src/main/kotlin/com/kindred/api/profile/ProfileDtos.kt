package com.kindred.api.profile

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import kotlin.math.roundToInt

data class UpdateProfileRequest(
    @field:NotBlank @field:Size(max = 100)
    val displayName: String,
    @field:Size(max = 2000)
    val bio: String? = null,
    val gender: Gender? = null,
    @field:Size(max = 10)
    val lookingFor: List<@NotBlank @Size(max = 64) String>? = null,
    @field:Size(max = 4)
    val relationshipStyles: List<RelationshipStyle>? = null,
    @field:Size(max = 20)
    val interests: List<@NotBlank @Size(max = 64) String>? = null,
)

data class UpdateLocationRequest(
    @field:Min(-90) @field:Max(90)
    val lat: Double,
    @field:Min(-180) @field:Max(180)
    val lng: Double,
    val visibility: LocationVisibility? = null,
)

data class InterestResponse(val slug: String, val label: String) {
    companion object {
        fun from(interest: Interest) = InterestResponse(interest.slug, interest.label)
    }
}

data class ProfileResponse(
    val userId: Long,
    val displayName: String,
    val bio: String?,
    val gender: Gender?,
    val lookingFor: List<String>,
    val relationshipStyles: List<RelationshipStyle>,
    val interests: List<InterestResponse>,
    val locationSet: Boolean,
    val locationVisibility: LocationVisibility,
    val lastActiveAt: Instant,
) {
    companion object {
        fun from(profile: Profile) = ProfileResponse(
            userId = profile.userId,
            displayName = profile.displayName,
            bio = profile.bio,
            gender = profile.gender,
            lookingFor = profile.lookingFor ?: emptyList(),
            relationshipStyles = profile.relationshipStyles ?: emptyList(),
            interests = profile.interests.map(InterestResponse::from).sortedBy { it.slug },
            locationSet = profile.locationSet,
            locationVisibility = profile.locationVisibility,
            lastActiveAt = profile.lastActiveAt,
        )
    }
}

data class NearbyProfileResponse(
    val userId: Long,
    val displayName: String,
    // whole km — deliberately coarse so distance can't be used for trilateration
    val distanceKm: Int,
) {
    companion object {
        fun from(view: NearbyProfileView) = NearbyProfileResponse(
            userId = view.userId,
            displayName = view.displayName,
            distanceKm = (view.distanceMeters / 1000.0).roundToInt(),
        )
    }
}
