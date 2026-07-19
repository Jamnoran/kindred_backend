package com.kindred.api.premium

import com.fasterxml.jackson.databind.ObjectMapper
import com.stripe.model.checkout.Session
import com.stripe.net.Webhook
import com.stripe.param.checkout.SessionCreateParams
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class StripeCheckoutServiceTest {

    private val stripe: StripeClient = mock()
    private val premiumService: PremiumService = mock()
    private val props = StripeProperties(
        secretKey = "sk_test_123",
        webhookSecret = "whsec_test_secret",
        priceId = "price_123",
        successUrl = "http://localhost:3000/premium/success",
        cancelUrl = "http://localhost:3000/premium/cancelled",
    )
    private val service = StripeCheckoutService(stripe, premiumService, props, ObjectMapper())

    /** A real signed webhook payload, the way Stripe's SDK verifies it. */
    private fun signed(payload: String): String {
        val timestamp = System.currentTimeMillis() / 1000
        val signature = Webhook.Util.computeHmacSha256(props.webhookSecret, "$timestamp.$payload")
        return "t=$timestamp,v1=$signature"
    }

    private fun checkoutCompletedPayload(
        clientReferenceId: String? = "1",
        paymentStatus: String = "paid",
        type: String = "checkout.session.completed",
    ): String {
        val ref = clientReferenceId?.let { "\"$it\"" } ?: "null"
        return """
            {"id":"evt_1","object":"event","api_version":"2026-01-01","type":"$type",
             "data":{"object":{"id":"cs_1","object":"checkout.session",
               "client_reference_id":$ref,"payment_status":"$paymentStatus"}}}
        """.trimIndent()
    }

    @Test
    fun `checkout builds a one-time payment session tagged with the user id`() {
        val session: Session = mock { on { url } doReturn "https://checkout.stripe.com/pay/cs_1" }
        whenever(stripe.createCheckoutSession(any())).thenReturn(session)

        val response = service.createCheckout(1L, "alice@example.com")

        assertEquals("https://checkout.stripe.com/pay/cs_1", response.checkoutUrl)
        val params = argumentCaptor<SessionCreateParams>().apply { verify(stripe).createCheckoutSession(capture()) }.firstValue
        assertEquals(SessionCreateParams.Mode.PAYMENT, params.mode)
        assertEquals("1", params.clientReferenceId)
        assertEquals("alice@example.com", params.customerEmail)
        assertEquals("price_123", params.lineItems.single().price)
        assertEquals(1L, params.lineItems.single().quantity)
        assertEquals(props.successUrl, params.successUrl)
        assertEquals(props.cancelUrl, params.cancelUrl)
    }

    @Test
    fun `already-premium accounts cannot start a checkout`() {
        whenever(premiumService.isPremium(1L)).thenReturn(true)

        assertThrows<AlreadyPremiumException> { service.createCheckout(1L, "alice@example.com") }
        verify(stripe, never()).createCheckoutSession(any())
    }

    @Test
    fun `paid checkout completion grants premium to the referenced user`() {
        val payload = checkoutCompletedPayload(clientReferenceId = "42")

        service.handleWebhook(payload, signed(payload))

        verify(premiumService).grant(42L)
    }

    @Test
    fun `async payment success also grants`() {
        val payload = checkoutCompletedPayload(clientReferenceId = "42", type = "checkout.session.async_payment_succeeded")

        service.handleWebhook(payload, signed(payload))

        verify(premiumService).grant(42L)
    }

    @Test
    fun `a bad or missing signature is rejected, never processed`() {
        val payload = checkoutCompletedPayload()

        assertThrows<InvalidStripeWebhookException> { service.handleWebhook(payload, "t=1,v1=deadbeef") }
        assertThrows<InvalidStripeWebhookException> { service.handleWebhook(payload, null) }
        // signed with a *different* secret
        val timestamp = System.currentTimeMillis() / 1000
        val wrong = "t=$timestamp,v1=" + Webhook.Util.computeHmacSha256("whsec_other", "$timestamp.$payload")
        assertThrows<InvalidStripeWebhookException> { service.handleWebhook(payload, wrong) }
        verify(premiumService, never()).grant(any())
    }

    @Test
    fun `unpaid sessions and unrelated events are acknowledged without granting`() {
        val unpaid = checkoutCompletedPayload(paymentStatus = "unpaid")
        service.handleWebhook(unpaid, signed(unpaid))

        val unrelated = checkoutCompletedPayload(type = "payment_intent.created")
        service.handleWebhook(unrelated, signed(unrelated))

        verify(premiumService, never()).grant(any())
    }

    @Test
    fun `a paid session without a client reference is logged, not granted`() {
        val payload = checkoutCompletedPayload(clientReferenceId = null)

        service.handleWebhook(payload, signed(payload))

        verify(premiumService, never()).grant(any())
    }
}
