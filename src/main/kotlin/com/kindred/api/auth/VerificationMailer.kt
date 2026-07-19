package com.kindred.api.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Sends the email-verification link. Swap the implementation for real SMTP before launch. */
interface VerificationMailer {
    fun sendVerificationEmail(email: String, token: String)
}

/**
 * Dev/self-host default: logs the verification link instead of sending mail, so the
 * flow is testable without an SMTP server. Never use in production — it puts a live
 * credential in the logs.
 */
@Component
class LoggingVerificationMailer(
    @param:Value("\${kindred.web-base-url:http://localhost:3000}") private val webBaseUrl: String,
) : VerificationMailer {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendVerificationEmail(email: String, token: String) {
        log.info("Verification link for {}: {}/verify-email?token={}", email, webBaseUrl, token)
    }
}
