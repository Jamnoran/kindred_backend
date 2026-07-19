package com.kindred.api.auth

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.Period

@Service
class AuthService(
    private val users: UserRepository,
    private val tokens: EmailVerificationTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val mailer: VerificationMailer,
    private val clock: Clock,
) {

    companion object {
        const val MIN_AGE_YEARS = 18
        val TOKEN_TTL: Duration = Duration.ofHours(24)
    }

    private val random = SecureRandom()

    @Transactional
    fun signup(email: String, rawPassword: String, dob: LocalDate): User {
        val normalizedEmail = normalizeEmail(email)
        if (Period.between(dob, LocalDate.now(clock)).years < MIN_AGE_YEARS) {
            throw UnderageSignupException()
        }
        if (users.existsByEmail(normalizedEmail)) {
            throw EmailAlreadyRegisteredException()
        }
        val user = users.save(
            User(
                email = normalizedEmail,
                passwordHash = passwordEncoder.encode(rawPassword),
                dob = dob,
                createdAt = clock.instant(),
            ),
        )
        issueVerificationToken(user)
        return user
    }

    @Transactional
    fun verifyEmail(token: String): User {
        val stored = tokens.findById(token).orElseThrow { InvalidVerificationTokenException() }
        if (stored.expiresAt.isBefore(clock.instant())) {
            tokens.deleteByToken(token)
            throw InvalidVerificationTokenException()
        }
        val user = users.findById(stored.userId).orElseThrow { InvalidVerificationTokenException() }
        if (user.emailVerified) return user
        user.emailVerified = true
        tokens.deleteByUserId(user.id!!)
        return users.save(user)
    }

    /** Always succeeds from the caller's perspective — no account enumeration via this endpoint. */
    @Transactional
    fun resendVerification(email: String) {
        val user = users.findByEmail(normalizeEmail(email)) ?: return
        if (user.emailVerified || user.deletedAt != null) return
        issueVerificationToken(user)
    }

    private fun issueVerificationToken(user: User) {
        tokens.deleteByUserId(user.id!!)
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        tokens.save(
            EmailVerificationToken(
                token = token,
                userId = user.id!!,
                expiresAt = clock.instant().plus(TOKEN_TTL),
                createdAt = clock.instant(),
            ),
        )
        mailer.sendVerificationEmail(user.email, token)
    }

    private fun normalizeEmail(email: String) = email.trim().lowercase()
}
