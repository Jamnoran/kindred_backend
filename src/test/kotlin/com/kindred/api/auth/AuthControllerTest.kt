package com.kindred.api.auth

import com.kindred.api.config.SecurityConfig
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate

/**
 * Web-slice test with the real security chain (SecurityConfig imported): exercises
 * routing, validation, error mapping, CSRF, and the login session flow. AuthService
 * and AuthenticationManager are mocked — DB-backed behavior is covered by
 * AuthServiceTest.
 */
@WebMvcTest(AuthController::class)
@Import(SecurityConfig::class)
class AuthControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var authService: AuthService

    @MockitoBean
    lateinit var authenticationManager: AuthenticationManager

    private fun testUser(verified: Boolean = false) = User(
        id = 1L,
        email = "new@example.com",
        passwordHash = "{bcrypt}hash",
        emailVerified = verified,
        dob = LocalDate.of(2000, 1, 1),
    )

    private fun principal(verified: Boolean = true) =
        KindredUserDetails(id = 1L, email = "new@example.com", passwordHash = "{bcrypt}hash", emailVerified = verified)

    @Test
    fun `signup returns 201 with the new user`() {
        whenever(authService.signup(any(), any(), any())).thenReturn(testUser())

        mockMvc.perform(
            post("/api/v1/auth/signup").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new@example.com","password":"password123","dob":"2000-01-01"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.email").value("new@example.com"))
            .andExpect(jsonPath("$.emailVerified").value(false))
    }

    @Test
    fun `signup maps underage to 422`() {
        whenever(authService.signup(any(), any(), any())).thenThrow(UnderageSignupException())

        mockMvc.perform(
            post("/api/v1/auth/signup").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"kid@example.com","password":"password123","dob":"2010-01-01"}"""),
        )
            .andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `signup maps duplicate email to 409`() {
        whenever(authService.signup(any(), any(), any())).thenThrow(EmailAlreadyRegisteredException())

        mockMvc.perform(
            post("/api/v1/auth/signup").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"taken@example.com","password":"password123","dob":"2000-01-01"}"""),
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `signup rejects invalid payloads with field errors`() {
        mockMvc.perform(
            post("/api/v1/auth/signup").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"not-an-email","password":"short","dob":"2000-01-01"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.email").exists())
            .andExpect(jsonPath("$.errors.password").exists())
    }

    @Test
    fun `mutating requests without a CSRF token are rejected`() {
        // A missing token surfaces as 401 (entry point) or 403 depending on the
        // denied-handler path — either way the request must not reach the service
        mockMvc.perform(
            post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new@example.com","password":"password123","dob":"2000-01-01"}"""),
        )
            .andExpect(status().is4xxClientError)
        verifyNoInteractions(authService)
    }

    @Test
    fun `mutating requests with an invalid CSRF token are 403`() {
        mockMvc.perform(
            post("/api/v1/auth/signup").with(csrf().useInvalidToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new@example.com","password":"password123","dob":"2000-01-01"}"""),
        )
            .andExpect(status().isForbidden)
        verifyNoInteractions(authService)
    }

    @Test
    fun `verify-email returns the verified user`() {
        whenever(authService.verifyEmail("sometoken")).thenReturn(testUser(verified = true))

        mockMvc.perform(
            post("/api/v1/auth/verify-email").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"sometoken"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.emailVerified").value(true))
    }

    @Test
    fun `verify-email maps bad tokens to 400`() {
        whenever(authService.verifyEmail(any())).thenThrow(InvalidVerificationTokenException())

        mockMvc.perform(
            post("/api/v1/auth/verify-email").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"token":"bad"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `resend-verification always returns 204`() {
        mockMvc.perform(
            post("/api/v1/auth/resend-verification").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"ghost@example.com"}"""),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `login authenticates and stores the session context`() {
        val principal = principal(verified = true)
        whenever(authenticationManager.authenticate(any())).thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities),
        )

        mockMvc.perform(
            post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new@example.com","password":"password123"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(request().sessionAttribute("SPRING_SECURITY_CONTEXT", notNullValue()))
    }

    @Test
    fun `login with an unverified email is 403`() {
        val principal = principal(verified = false)
        whenever(authenticationManager.authenticate(any())).thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities),
        )

        mockMvc.perform(
            post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new@example.com","password":"password123"}"""),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `login with a banned account is 403`() {
        val principal = principal(verified = true)
        whenever(authenticationManager.authenticate(any())).thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities),
        )
        whenever(authService.assertNotBanned(1L)).thenThrow(AccountBannedException())

        mockMvc.perform(
            post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new@example.com","password":"password123"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(request().sessionAttribute("SPRING_SECURITY_CONTEXT", nullValue()))
    }

    @Test
    fun `login with bad credentials is 401`() {
        whenever(authenticationManager.authenticate(any())).thenThrow(BadCredentialsException("bad"))

        mockMvc.perform(
            post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"new@example.com","password":"wrong"}"""),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `me requires authentication`() {
        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `me returns the current principal`() {
        mockMvc.perform(get("/api/v1/auth/me").with(user(principal())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.email").value("new@example.com"))
    }

    @Test
    fun `logout invalidates the session and returns 204`() {
        mockMvc.perform(post("/api/v1/auth/logout").with(csrf()).with(user(principal())))
            .andExpect(status().isNoContent)
    }
}
