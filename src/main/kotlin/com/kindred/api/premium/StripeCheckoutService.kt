package com.kindred.api.premium

import com.fasterxml.jackson.databind.ObjectMapper
import com.stripe.exception.SignatureVerificationException
import com.stripe.net.Webhook
import com.stripe.param.checkout.SessionCreateParams
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * The premium purchase flow (docs/STRIPE_SETUP.md): the client asks for a Checkout
 * Session and redirects to Stripe; Stripe redirects back and — authoritatively —
 * calls the webhook, which is the ONLY place premium is granted. Never grant from
 * the success redirect: the user can open that URL without paying.
 */
@Service
class StripeCheckoutService(
    private val stripe: StripeClient,
    private val premiumService: PremiumService,
    private val props: StripeProperties,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        private val log = LoggerFactory.getLogger(StripeCheckoutService::class.java)

        /** Both fire with `payment_status: "paid"` depending on the payment method. */
        private val COMPLETION_EVENTS = setOf(
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded",
        )
    }

    fun createCheckout(userId: Long, email: String): CheckoutSessionResponse {
        if (premiumService.isPremium(userId)) throw AlreadyPremiumException()
        val session = stripe.createCheckoutSession(
            SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                // how the webhook maps the payment back to our user
                .setClientReferenceId(userId.toString())
                .setCustomerEmail(email)
                .addLineItem(
                    SessionCreateParams.LineItem.builder()
                        .setPrice(props.priceId)
                        .setQuantity(1)
                        .build(),
                )
                .setSuccessUrl(props.successUrl)
                .setCancelUrl(props.cancelUrl)
                .build(),
        )
        return CheckoutSessionResponse(checkoutUrl = session.url)
    }

    /**
     * Signature-verified webhook entry point. Grants premium on paid checkout
     * completion; everything else is acknowledged and ignored (Stripe retries
     * non-2xx responses, so only signature failures may reject).
     */
    fun handleWebhook(payload: String, signatureHeader: String?) {
        val event = try {
            Webhook.constructEvent(payload, signatureHeader.orEmpty(), props.webhookSecret)
        } catch (e: SignatureVerificationException) {
            throw InvalidStripeWebhookException()
        } catch (e: RuntimeException) { // malformed JSON payloads
            throw InvalidStripeWebhookException()
        }
        if (event.type !in COMPLETION_EVENTS) return

        // Read the raw session JSON instead of SDK model deserialization so a
        // dashboard API-version bump can't silently break grants.
        val session = objectMapper.readTree(event.dataObjectDeserializer.rawJson)
        if (session.path("payment_status").asText() != "paid") return
        val userId = session.path("client_reference_id").asText(null)?.toLongOrNull()
        if (userId == null) {
            log.error("Stripe event {} ({}) is paid but has no usable client_reference_id", event.id, event.type)
            return
        }
        premiumService.grant(userId) // idempotent — safe under Stripe's retry semantics
        log.info("premium granted to user {} via stripe event {}", userId, event.id)
    }
}
