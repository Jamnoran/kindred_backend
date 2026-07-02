package com.kindred.api.auth

import org.springframework.data.jpa.repository.JpaRepository

interface EmailVerificationTokenRepository : JpaRepository<EmailVerificationToken, String> {
    fun deleteByUserId(userId: Long)
}
