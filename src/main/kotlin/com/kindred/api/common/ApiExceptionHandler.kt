package com.kindred.api.common

import com.kindred.api.auth.EmailAlreadyRegisteredException
import com.kindred.api.auth.EmailNotVerifiedException
import com.kindred.api.auth.InvalidVerificationTokenException
import com.kindred.api.auth.UnderageSignupException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** RFC 9457 problem-detail responses for domain and validation errors. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    fun emailTaken(e: EmailAlreadyRegisteredException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(UnderageSignupException::class)
    fun underage(e: UnderageSignupException) = problem(HttpStatus.UNPROCESSABLE_ENTITY, e.message)

    @ExceptionHandler(InvalidVerificationTokenException::class)
    fun invalidToken(e: InvalidVerificationTokenException) = problem(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(EmailNotVerifiedException::class)
    fun emailNotVerified(e: EmailNotVerifiedException) = problem(HttpStatus.FORBIDDEN, e.message)

    // Covers BadCredentialsException from the login endpoint; never echo details
    @ExceptionHandler(AuthenticationException::class)
    fun badCredentials(e: AuthenticationException) = problem(HttpStatus.UNAUTHORIZED, "invalid credentials")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(e: MethodArgumentNotValidException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "validation failed").apply {
            setProperty(
                "errors",
                e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") },
            )
        }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(e: HttpMessageNotReadableException) =
        problem(HttpStatus.BAD_REQUEST, "malformed request body")

    private fun problem(status: HttpStatus, detail: String?): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase)
}
