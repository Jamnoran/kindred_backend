package com.kindred.api.premium

import com.kindred.api.auth.User
import com.kindred.api.auth.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PremiumServiceTest {

    private val users: UserRepository = mock()
    private val now = Instant.parse("2026-07-05T12:00:00Z")
    private val service = PremiumService(users, Clock.fixed(now, ZoneOffset.UTC))

    private fun user(premiumSince: Instant? = null) = User(
        id = 1L, email = "a@example.com", passwordHash = "h",
        dob = LocalDate.of(1990, 1, 1), premiumSince = premiumSince,
    )

    @Test
    fun `premium is whoever has a purchase timestamp`() {
        whenever(users.findPremiumIds(listOf(1L))).thenReturn(setOf(1L))
        whenever(users.findPremiumIds(listOf(2L))).thenReturn(emptySet())

        assertTrue(service.isPremium(1L))
        assertFalse(service.isPremium(2L))
    }

    @Test
    fun `anyPremium is true when at least one of the group upgraded`() {
        whenever(users.findPremiumIds(listOf(1L, 2L))).thenReturn(setOf(2L))

        assertTrue(service.anyPremium(listOf(1L, 2L)))
        assertFalse(service.anyPremium(emptyList()))
        verify(users, never()).findPremiumIds(emptyList())
    }

    @Test
    fun `status reports the purchase`() {
        whenever(users.findById(1L)).thenReturn(Optional.of(user(premiumSince = now)))

        assertEquals(PremiumStatusResponse(premium = true, premiumSince = now), service.status(1L))
    }

    @Test
    fun `grant stamps the purchase time once`() {
        whenever(users.findById(1L)).thenReturn(Optional.of(user()))

        assertEquals(PremiumStatusResponse(premium = true, premiumSince = now), service.grant(1L))
        verify(users).save(any())
    }

    @Test
    fun `grant is idempotent - a retried webhook keeps the original purchase time`() {
        val original = Instant.parse("2026-01-01T00:00:00Z")
        whenever(users.findById(1L)).thenReturn(Optional.of(user(premiumSince = original)))

        assertEquals(PremiumStatusResponse(premium = true, premiumSince = original), service.grant(1L))
        verify(users, never()).save(any())
    }
}
