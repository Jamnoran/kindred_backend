package com.kindred.api.premium

import com.kindred.api.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(StripeWebhookController::class)
@Import(SecurityConfig::class)
class StripeWebhookControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var checkout: StripeCheckoutService

    /** Stripe sends no session cookie and no CSRF header — the endpoint must still accept it. */
    @Test
    fun `webhook is public and csrf-exempt`() {
        mockMvc.perform(
            post("/api/v1/stripe/webhook")
                .header("Stripe-Signature", "t=1,v1=sig")
                .content("""{"type":"checkout.session.completed"}"""),
        ).andExpect(status().isOk)

        verify(checkout).handleWebhook("""{"type":"checkout.session.completed"}""", "t=1,v1=sig")
    }

    @Test
    fun `bad signatures are 400`() {
        whenever(checkout.handleWebhook("{}", null)).thenThrow(InvalidStripeWebhookException())

        mockMvc.perform(post("/api/v1/stripe/webhook").content("{}"))
            .andExpect(status().isBadRequest)
    }
}
