package com.kindred.api.discovery

import com.kindred.api.auth.KindredUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1")
class DiscoveryController(
    private val discoveryService: DiscoveryService,
    private val preferencesService: PreferencesService,
    private val likeService: LikeService,
) {

    @GetMapping("/discovery")
    fun discover(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int,
    ): List<DiscoveryCard> = discoveryService.discover(principal.id, limit)

    @GetMapping("/preferences")
    fun preferences(@AuthenticationPrincipal principal: KindredUserDetails): PreferencesResponse =
        PreferencesResponse.from(preferencesService.get(principal.id))

    @PutMapping("/preferences")
    fun updatePreferences(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @Valid @RequestBody req: UpdatePreferencesRequest,
    ): PreferencesResponse = PreferencesResponse.from(preferencesService.update(principal.id, req))

    @PostMapping("/likes")
    @ResponseStatus(HttpStatus.CREATED)
    fun react(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @Valid @RequestBody req: ReactRequest,
    ): ReactResponse = likeService.react(principal.id, req.toUserId, req.kind)

    /** "Who liked you" — free for everyone, plainly visible (§7). */
    @GetMapping("/likes/received")
    fun receivedLikes(@AuthenticationPrincipal principal: KindredUserDetails): List<ReceivedLikeResponse> =
        likeService.receivedLikes(principal.id)
}
