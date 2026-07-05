package com.kindred.api.premium

import com.kindred.api.auth.KindredUserDetails
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/premium")
class PremiumController(
    private val premiumService: PremiumService,
    private val stripeCheckoutService: StripeCheckoutService,
) {

    /** The caller's own premium status; poll this after returning from checkout. */
    @GetMapping
    fun status(@AuthenticationPrincipal principal: KindredUserDetails): PremiumStatusResponse =
        premiumService.status(principal.id)

    /** Starts the one-time purchase: redirect the browser to the returned Stripe URL. */
    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    fun checkout(@AuthenticationPrincipal principal: KindredUserDetails): CheckoutSessionResponse =
        stripeCheckoutService.createCheckout(principal.id, principal.username)
}
