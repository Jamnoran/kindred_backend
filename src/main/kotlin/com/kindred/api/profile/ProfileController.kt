package com.kindred.api.profile

import com.kindred.api.auth.KindredUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1")
class ProfileController(private val profileService: ProfileService) {

    @GetMapping("/profile")
    fun ownProfile(@AuthenticationPrincipal principal: KindredUserDetails): ProfileResponse =
        ProfileResponse.from(profileService.getOwn(principal.id))

    @PutMapping("/profile")
    fun upsertProfile(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @Valid @RequestBody req: UpdateProfileRequest,
    ): ProfileResponse = ProfileResponse.from(profileService.upsert(principal.id, req))

    @PutMapping("/profile/location")
    fun updateLocation(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @Valid @RequestBody req: UpdateLocationRequest,
    ): ProfileResponse = ProfileResponse.from(profileService.updateLocation(principal.id, req))

    @GetMapping("/profiles/{userId}")
    fun matchProfile(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @PathVariable userId: Long,
    ): MatchProfileResponse = profileService.getMatchProfile(principal.id, userId)

    @GetMapping("/profiles/nearby")
    fun nearby(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @RequestParam(defaultValue = "50") @Min(1) @Max(500) radiusKm: Int,
    ): List<NearbyProfileResponse> =
        profileService.nearby(principal.id, radiusKm).map(NearbyProfileResponse::from)

    @GetMapping("/interests")
    fun interests(): List<InterestResponse> =
        profileService.listInterests().map(InterestResponse::from)
}
