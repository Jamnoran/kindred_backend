package com.kindred.api.premium

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(PremiumController::class)
@Import(SecurityConfig::class)
class PremiumControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var premiumService: PremiumService

    @MockitoBean
    lateinit var stripeCheckoutService: StripeCheckoutService

    private val alice = KindredUserDetails(id = 1L, email = "alice@example.com", passwordHash = "h", emailVerified = true)

    @Test
    fun `premium status requires authentication`() {
        mockMvc.perform(get("/api/v1/premium")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `premium status reports the caller's upgrade`() {
        whenever(premiumService.status(1L)).thenReturn(
            PremiumStatusResponse(premium = true, premiumSince = Instant.parse("2026-07-05T12:00:00Z")),
        )

        mockMvc.perform(get("/api/v1/premium").with(user(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.premium").value(true))
            .andExpect(jsonPath("$.premiumSince").value("2026-07-05T12:00:00Z"))
    }

    @Test
    fun `checkout returns the stripe redirect url`() {
        whenever(stripeCheckoutService.createCheckout(1L, "alice@example.com")).thenReturn(
            CheckoutSessionResponse(checkoutUrl = "https://checkout.stripe.com/pay/cs_1"),
        )

        mockMvc.perform(post("/api/v1/premium/checkout").with(user(alice)).with(csrf()))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.checkoutUrl").value("https://checkout.stripe.com/pay/cs_1"))
    }

    @Test
    fun `checkout for an already-premium account is a conflict`() {
        whenever(stripeCheckoutService.createCheckout(1L, "alice@example.com")).thenThrow(AlreadyPremiumException())

        mockMvc.perform(post("/api/v1/premium/checkout").with(user(alice)).with(csrf()))
            .andExpect(status().isConflict)
    }
}
