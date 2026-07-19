package com.kindred.api.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Session-based auth (ARCHITECTURE.md §3). Logout is handled by Spring Security's
 * logout filter at POST /api/v1/auth/logout — see SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val authenticationManager: AuthenticationManager,
    private val securityContextRepository: SecurityContextRepository,
) {

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody req: SignupRequest): UserResponse =
        UserResponse.from(authService.signup(req.email, req.password, req.dob))

    @PostMapping("/verify-email")
    fun verifyEmail(@Valid @RequestBody req: VerifyEmailRequest): UserResponse =
        UserResponse.from(authService.verifyEmail(req.token))

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resendVerification(@Valid @RequestBody req: ResendVerificationRequest) {
        authService.resendVerification(req.email)
    }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody req: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): UserResponse {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(req.email.trim().lowercase(), req.password),
        )
        val principal = authentication.principal as KindredUserDetails
        // Only reveal the unverified/banned state after the password checked out
        if (!principal.emailVerified) {
            throw EmailNotVerifiedException()
        }
        authService.assertNotBanned(principal.id)
        // Session fixation protection: never carry a pre-login session across authentication
        request.getSession(false)?.invalidate()
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
        return UserResponse(principal.id, principal.username, principal.emailVerified)
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: KindredUserDetails): UserResponse =
        UserResponse(principal.id, principal.username, principal.emailVerified)
}
