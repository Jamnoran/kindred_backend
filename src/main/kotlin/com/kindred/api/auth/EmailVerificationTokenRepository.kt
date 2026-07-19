package com.kindred.api.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface EmailVerificationTokenRepository : JpaRepository<EmailVerificationToken, String> {
    @Modifying
    @Query("DELETE FROM EmailVerificationToken t WHERE t.userId = :userId")
    fun deleteByUserId(userId: Long)

    @Modifying
    @Query("DELETE FROM EmailVerificationToken t WHERE t.token = :token")
    fun deleteByToken(token: String)
}
