package com.kindred.api.premium

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Called by Stripe, not by users: public (no session) and CSRF-exempt — see
 * SecurityConfig. Authenticity comes from the Stripe-Signature header instead.
 */
@RestController
@RequestMapping("/api/v1/stripe")
class StripeWebhookController(private val checkout: StripeCheckoutService) {

    @PostMapping("/webhook")
    fun webhook(
        @RequestBody payload: String,
        @RequestHeader("Stripe-Signature", required = false) signature: String?,
    ) {
        checkout.handleWebhook(payload, signature)
    }
}
