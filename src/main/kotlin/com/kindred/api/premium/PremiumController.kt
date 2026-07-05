package com.kindred.api.premium

import com.kindred.api.auth.KindredUserDetails
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/premium")
class PremiumController(private val premiumService: PremiumService) {

    /** The caller's own premium status; purchasing happens via the payment provider. */
    @GetMapping
    fun status(@AuthenticationPrincipal principal: KindredUserDetails): PremiumStatusResponse =
        premiumService.status(principal.id)
}
