package com.kindred.api.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Past
import jakarta.validation.constraints.Size
import java.time.LocalDate

data class SignupRequest(
    @field:NotBlank @field:Email @field:Size(max = 255)
    val email: String,
    // 72 bytes is the bcrypt input limit; longer passwords would be silently truncated
    @field:NotBlank @field:Size(min = 8, max = 72)
    val password: String,
    @field:Past
    val dob: LocalDate,
)

data class LoginRequest(
    @field:NotBlank @field:Email
    val email: String,
    @field:NotBlank
    val password: String,
)

data class VerifyEmailRequest(
    @field:NotBlank
    val token: String,
)

data class ResendVerificationRequest(
    @field:NotBlank @field:Email
    val email: String,
)

data class UserResponse(
    val id: Long,
    val email: String,
    val emailVerified: Boolean,
) {
    companion object {
        fun from(user: User) = UserResponse(
            id = requireNotNull(user.id),
            email = user.email,
            emailVerified = user.emailVerified,
        )
    }
}
