package com.kindred.api.auth

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

/**
 * Session principal. `emailVerified` is checked *after* password verification (in the
 * login endpoint) rather than via [isEnabled], so an unverified-account response can
 * never be used to probe whether an email is registered.
 */
class KindredUserDetails(
    val id: Long,
    private val email: String,
    private val passwordHash: String,
    val emailVerified: Boolean,
) : UserDetails {
    override fun getUsername(): String = email
    override fun getPassword(): String = passwordHash
    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_USER"))
}

@Service
class KindredUserDetailsService(private val users: UserRepository) : UserDetailsService {

    override fun loadUserByUsername(username: String): UserDetails {
        val user = users.findByEmail(username.trim().lowercase())
            ?: throw UsernameNotFoundException("unknown user")
        if (user.deletedAt != null) {
            // Soft-deleted accounts behave exactly like nonexistent ones
            throw UsernameNotFoundException("unknown user")
        }
        return KindredUserDetails(
            id = requireNotNull(user.id),
            email = user.email,
            passwordHash = user.passwordHash,
            emailVerified = user.emailVerified,
        )
    }
}
