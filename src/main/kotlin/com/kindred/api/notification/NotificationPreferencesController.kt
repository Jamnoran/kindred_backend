package com.kindred.api.notification

import com.kindred.api.auth.KindredUserDetails
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/notification-preferences")
class NotificationPreferencesController(
    private val preferenceService: NotificationPreferenceService,
) {

    /** The caller's full type × channel grid (defaults filled in; everything starts enabled). */
    @GetMapping
    fun preferences(@AuthenticationPrincipal principal: KindredUserDetails): NotificationPreferencesResponse =
        preferenceService.preferences(principal.id)

    /** Full replace: combos missing from the body reset to their default (enabled). */
    @PutMapping
    fun replace(
        @AuthenticationPrincipal principal: KindredUserDetails,
        @RequestBody request: UpdateNotificationPreferencesRequest,
    ): NotificationPreferencesResponse = preferenceService.replace(principal.id, request.preferences)
}
