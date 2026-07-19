package com.kindred.api.premium

import java.time.Instant

/** 402 — the caller must complete the one-time premium purchase first. */
class PremiumRequiredException(feature: String) :
    RuntimeException("$feature requires a premium account — a one-time upgrade")

/** 409 — no point starting a checkout for an account that already upgraded. */
class AlreadyPremiumException : RuntimeException("account is already premium")

/** 400 on the webhook — bad/missing Stripe-Signature or unparseable payload. */
class InvalidStripeWebhookException : RuntimeException("invalid stripe webhook")

data class PremiumStatusResponse(
    val premium: Boolean,
    val premiumSince: Instant?,
)

/** Redirect the user's browser to [checkoutUrl]; the webhook grants premium after payment. */
data class CheckoutSessionResponse(
    val checkoutUrl: String,
)
