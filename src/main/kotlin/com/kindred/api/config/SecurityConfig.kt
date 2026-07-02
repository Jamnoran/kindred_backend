package com.kindred.api.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.util.StringUtils
import java.util.function.Supplier

/**
 * Session-based security (Spring Session backed by Redis — see ARCHITECTURE.md §3).
 * Public: API docs, health, meta, and the pre-login auth endpoints. Everything else
 * requires a session. Unauthenticated API calls get a plain 401 instead of a
 * login-page redirect.
 *
 * Hardening: session-fixation protection on login (AuthController invalidates any
 * pre-login session), cookie flags in application.yml (HttpOnly/SameSite/Secure),
 * and CSRF via the double-submit cookie pattern below.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        securityContextRepository: SecurityContextRepository,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/actuator/health",
                        "/api/v1/meta",
                        "/api/v1/auth/signup",
                        "/api/v1/auth/login",
                        "/api/v1/auth/verify-email",
                        "/api/v1/auth/resend-verification",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .securityContext { it.securityContextRepository(securityContextRepository) }
            // SPA CSRF setup (Spring Security reference): token in a JS-readable cookie,
            // client echoes it back in the X-XSRF-TOKEN header on every mutating request.
            .csrf { csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                csrf.csrfTokenRequestHandler(SpaCsrfTokenRequestHandler())
            }
            .logout { logout ->
                logout.logoutUrl("/api/v1/auth/logout")
                logout.logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                logout.deleteCookies("SESSION")
            }
            .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()

    @Bean
    fun authenticationManager(config: AuthenticationConfiguration): AuthenticationManager =
        config.authenticationManager

    /** Shared with AuthController so programmatic login persists the context into the session. */
    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()
}

/**
 * From the Spring Security reference ("Single-Page Applications"): BREACH-protect
 * server-rendered tokens, accept the plain token from the SPA's header, and force the
 * deferred token to be resolved so the cookie is written on every response that needs it.
 */
class SpaCsrfTokenRequestHandler : CsrfTokenRequestAttributeHandler() {
    private val plain = CsrfTokenRequestAttributeHandler()
    private val xor = XorCsrfTokenRequestAttributeHandler()

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        csrfToken: Supplier<CsrfToken>,
    ) {
        xor.handle(request, response, csrfToken)
        csrfToken.get()
    }

    override fun resolveCsrfTokenValue(request: HttpServletRequest, csrfToken: CsrfToken): String? {
        val headerValue = request.getHeader(csrfToken.headerName)
        return (if (StringUtils.hasText(headerValue)) plain else xor).resolveCsrfTokenValue(request, csrfToken)
    }
}
