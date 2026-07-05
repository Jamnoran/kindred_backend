package com.kindred.api.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(nullable = false, unique = true)
    var email: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(name = "email_verified", nullable = false)
    var emailVerified: Boolean = false,

    @Column(nullable = false)
    var dob: LocalDate,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),

    // Soft-delete marker; the GDPR erasure job hard-deletes later (ARCHITECTURE.md §9)
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,

    // One-time paid upgrade (never expires); NULL = free account
    @Column(name = "premium_since")
    var premiumSince: Instant? = null,
)
