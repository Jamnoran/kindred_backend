package com.kindred.api.premium

import com.stripe.model.checkout.Session
import com.stripe.net.RequestOptions
import com.stripe.param.checkout.SessionCreateParams
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

/** Stripe wiring for the one-time premium purchase — see docs/STRIPE_SETUP.md. */
@ConfigurationProperties("kindred.stripe")
data class StripeProperties(
    /** sk_test_… / sk_live_… — empty in dev means checkout calls will fail, gating still works */
    val secretKey: String,
    /** whsec_… from the dashboard webhook endpoint (or `stripe listen` locally) */
    val webhookSecret: String,
    /** price_… of the one-time premium Price */
    val priceId: String,
    val successUrl: String,
    val cancelUrl: String,
)

@Configuration
@EnableConfigurationProperties(StripeProperties::class)
class StripeConfig

/**
 * Thin wrapper around the SDK's static call so services stay unit-testable.
 * The API key is passed per request instead of mutating the global `Stripe.apiKey`.
 */
@Component
class StripeClient(private val props: StripeProperties) {

    fun createCheckoutSession(params: SessionCreateParams): Session =
        Session.create(params, RequestOptions.builder().setApiKey(props.secretKey).build())
}
