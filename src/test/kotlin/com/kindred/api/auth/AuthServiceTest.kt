package com.kindred.api.auth

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthServiceTest {

    private val users: UserRepository = mock()
    private val tokens: EmailVerificationTokenRepository = mock()
    private val passwordEncoder: PasswordEncoder = mock()
    private val mailer: VerificationMailer = mock()
    private val now = Instant.parse("2026-07-02T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val service = AuthService(users, tokens, passwordEncoder, mailer, clock)

    private fun user(
        id: Long = 1L,
        email: String = "someone@example.com",
        verified: Boolean = false,
        deletedAt: Instant? = null,
    ) = User(
        id = id,
        email = email,
        passwordHash = "{bcrypt}hash",
        emailVerified = verified,
        dob = LocalDate.of(2000, 1, 1),
        deletedAt = deletedAt,
    )

    @Test
    fun `signup normalizes email, hashes password, and sends a verification token`() {
        whenever(users.existsByEmail("new@example.com")).thenReturn(false)
        whenever(passwordEncoder.encode("password123")).thenReturn("{bcrypt}hash")
        whenever(users.save(any())).thenAnswer { (it.arguments[0] as User).apply { id = 7L } }
        whenever(tokens.save(any())).thenAnswer { it.arguments[0] }

        val user = service.signup(" New@Example.COM ", "password123", LocalDate.of(2000, 1, 1))

        assertEquals("new@example.com", user.email)
        assertEquals("{bcrypt}hash", user.passwordHash)
        assertFalse(user.emailVerified)
        verify(mailer).sendVerificationEmail(eq("new@example.com"), argThat { length == 64 })
    }

    @Test
    fun `signup allows exactly 18 years old today`() {
        whenever(users.existsByEmail(any())).thenReturn(false)
        whenever(passwordEncoder.encode(any())).thenReturn("{bcrypt}hash")
        whenever(users.save(any())).thenAnswer { (it.arguments[0] as User).apply { id = 7L } }
        whenever(tokens.save(any())).thenAnswer { it.arguments[0] }

        service.signup("adult@example.com", "password123", LocalDate.of(2008, 7, 2))

        verify(users).save(any())
    }

    @Test
    fun `signup rejects under-18s`() {
        assertThrows<UnderageSignupException> {
            service.signup("kid@example.com", "password123", LocalDate.of(2008, 7, 3))
        }
        verify(users, never()).save(any())
    }

    @Test
    fun `signup rejects duplicate email`() {
        whenever(users.existsByEmail("taken@example.com")).thenReturn(true)

        assertThrows<EmailAlreadyRegisteredException> {
            service.signup("taken@example.com", "password123", LocalDate.of(2000, 1, 1))
        }
        verify(users, never()).save(any())
    }

    @Test
    fun `verifyEmail marks the user verified and burns the token`() {
        val stored = EmailVerificationToken(token = "t".repeat(64), userId = 7L, expiresAt = now.plusSeconds(60))
        whenever(tokens.findById(stored.token)).thenReturn(Optional.of(stored))
        whenever(users.findById(7L)).thenReturn(Optional.of(user(id = 7L)))
        whenever(users.save(any())).thenAnswer { it.arguments[0] }

        val verified = service.verifyEmail(stored.token)

        assertTrue(verified.emailVerified)
        verify(tokens).deleteByUserId(7L)
    }

    @Test
    fun `verifyEmail rejects expired tokens and deletes them`() {
        val stored = EmailVerificationToken(token = "t".repeat(64), userId = 7L, expiresAt = now.minusSeconds(1))
        whenever(tokens.findById(stored.token)).thenReturn(Optional.of(stored))

        assertThrows<InvalidVerificationTokenException> { service.verifyEmail(stored.token) }
        verify(tokens).deleteByToken(stored.token)
    }

    @Test
    fun `verifyEmail rejects unknown tokens`() {
        whenever(tokens.findById(any())).thenReturn(Optional.empty())

        assertThrows<InvalidVerificationTokenException> { service.verifyEmail("nope") }
    }

    @Test
    fun `resendVerification is silent for unknown or already-verified emails`() {
        whenever(users.findByEmail("ghost@example.com")).thenReturn(null)
        whenever(users.findByEmail("done@example.com")).thenReturn(user(email = "done@example.com", verified = true))

        service.resendVerification("ghost@example.com")
        service.resendVerification("done@example.com")

        verify(mailer, never()).sendVerificationEmail(any(), any())
    }

    @Test
    fun `resendVerification reissues the token for unverified accounts`() {
        whenever(users.findByEmail("slow@example.com")).thenReturn(user(id = 9L, email = "slow@example.com"))
        whenever(tokens.save(any())).thenAnswer { it.arguments[0] }

        service.resendVerification("slow@example.com")

        verify(tokens).deleteByUserId(9L)
        verify(mailer).sendVerificationEmail(eq("slow@example.com"), any())
    }
}
