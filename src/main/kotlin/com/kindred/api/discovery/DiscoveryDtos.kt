package com.kindred.api.discovery

import com.kindred.api.photo.Photo
import com.kindred.api.photo.PhotoUrls
import com.kindred.api.profile.Gender
import com.kindred.api.profile.RelationshipStyle
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant

data class UpdatePreferencesRequest(
    @field:Min(1) @field:Max(500)
    val distanceKm: Int = 50,
    @field:Min(18) @field:Max(120)
    val ageMin: Int = 18,
    @field:Min(18) @field:Max(120)
    val ageMax: Int = 99,
    /** "Show me" — empty/omitted means everyone; enforced mutually in discovery. */
    @field:Size(max = 3)
    val genders: List<Gender>? = null,
    @field:Size(max = 10)
    val lookingFor: List<String>? = null,
    @field:Size(max = 4)
    val relationshipStyles: List<RelationshipStyle>? = null,
    @field:Size(max = 20)
    val dealbreakers: List<String>? = null,
    val weights: Map<String, Double>? = null,
)

data class PreferencesResponse(
    val distanceKm: Int,
    val ageMin: Int,
    val ageMax: Int,
    val genders: List<Gender>,
    val lookingFor: List<String>,
    val relationshipStyles: List<RelationshipStyle>,
    val dealbreakers: List<String>,
    val weights: DiscoveryScoring.Weights,
) {
    companion object {
        fun from(p: Preferences) = PreferencesResponse(
            distanceKm = p.distanceKm,
            ageMin = p.ageMin,
            ageMax = p.ageMax,
            genders = p.genders ?: emptyList(),
            lookingFor = p.lookingFor ?: emptyList(),
            relationshipStyles = p.relationshipStyles ?: emptyList(),
            dealbreakers = p.dealbreakers ?: emptyList(),
            weights = DiscoveryScoring.Weights.from(p.weights),
        )
    }
}

data class PhotoSummary(val urls: PhotoUrls?, val blurhash: String?) {
    companion object {
        fun from(photo: Photo?, publicBaseUrl: String): PhotoSummary? = photo?.let {
            com.kindred.api.photo.PhotoResponse.from(it, publicBaseUrl).let { r -> PhotoSummary(r.urls, r.blurhash) }
        }
    }
}

/** A discovery card: everything shown, including exactly why it ranked where it did. */
data class DiscoveryCard(
    val userId: Long,
    val displayName: String,
    val age: Int,
    val bio: String?,
    val gender: Gender?,
    val lookingFor: List<String>,
    val relationshipStyles: List<RelationshipStyle>,
    val interests: List<String>,
    val photo: PhotoSummary?,
    /** null when either side hides or hasn't set location */
    val distanceKm: Int?,
    val score: Double,
    val whyThisPerson: DiscoveryScoring.Factors,
)

data class ReactRequest(
    @field:NotNull
    val toUserId: Long,
    @field:NotNull
    val kind: LikeKind,
)

data class ReactResponse(
    val matched: Boolean,
    val matchId: Long? = null,
    val conversationId: Long? = null,
)

data class ReceivedLikeResponse(
    val userId: Long,
    val displayName: String,
    val kind: LikeKind,
    val likedAt: Instant,
    val photo: PhotoSummary?,
)
