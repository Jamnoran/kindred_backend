package com.kindred.api.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "email_verification_tokens")
class EmailVerificationToken(
    @Id
    @Column(length = 64)
    var token: String,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)
