package com.kindred.api.common

import com.kindred.api.auth.AccountBannedException
import com.kindred.api.auth.EmailAlreadyRegisteredException
import com.kindred.api.auth.EmailNotVerifiedException
import com.kindred.api.auth.InvalidVerificationTokenException
import com.kindred.api.auth.UnderageSignupException
import com.kindred.api.chat.ChatMediaNotFoundException
import com.kindred.api.chat.ChatMediaNotReadyException
import com.kindred.api.chat.ConversationNotFoundException
import com.kindred.api.chat.EmptyMessageException
import com.kindred.api.discovery.AlreadyReactedException
import com.kindred.api.discovery.CannotReactToSelfException
import com.kindred.api.discovery.ReactionTargetNotFoundException
import com.kindred.api.media.UnsupportedImageTypeException
import com.kindred.api.moderation.AdminAccessDeniedException
import com.kindred.api.moderation.CannotModerateAdminException
import com.kindred.api.moderation.CannotReportSelfException
import com.kindred.api.moderation.DuplicateReportException
import com.kindred.api.moderation.ModerationTargetNotFoundException
import com.kindred.api.moderation.ReportNotFoundException
import com.kindred.api.moderation.ReportNotOpenException
import com.kindred.api.moderation.ReportTargetNotFoundException
import com.kindred.api.moderation.UserAlreadyBannedException
import com.kindred.api.moderation.UserNotBannedException
import com.kindred.api.notification.DuplicateNotificationPreferenceException
import com.kindred.api.photo.InvalidStorageKeyException
import com.kindred.api.photo.PhotoLimitReachedException
import com.kindred.api.photo.PhotoNotFoundException
import com.kindred.api.premium.AlreadyPremiumException
import com.kindred.api.premium.InvalidStripeWebhookException
import com.kindred.api.premium.PremiumRequiredException
import com.kindred.api.profile.LocationNotSetException
import com.kindred.api.profile.ProfileNotFoundException
import com.kindred.api.profile.UnknownInterestException
import jakarta.validation.ConstraintViolationException
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

    @ExceptionHandler(AccountBannedException::class)
    fun accountBanned(e: AccountBannedException) = problem(HttpStatus.FORBIDDEN, e.message)

    @ExceptionHandler(ProfileNotFoundException::class)
    fun profileNotFound(e: ProfileNotFoundException) = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(UnknownInterestException::class)
    fun unknownInterest(e: UnknownInterestException) = problem(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(LocationNotSetException::class)
    fun locationNotSet(e: LocationNotSetException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(CannotReactToSelfException::class)
    fun reactToSelf(e: CannotReactToSelfException) = problem(HttpStatus.UNPROCESSABLE_ENTITY, e.message)

    @ExceptionHandler(AlreadyReactedException::class)
    fun alreadyReacted(e: AlreadyReactedException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(ReactionTargetNotFoundException::class)
    fun reactionTarget(e: ReactionTargetNotFoundException) = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(ConversationNotFoundException::class)
    fun conversationNotFound(e: ConversationNotFoundException) = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(EmptyMessageException::class)
    fun emptyMessage(e: EmptyMessageException) = problem(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(ChatMediaNotFoundException::class)
    fun chatMediaNotFound(e: ChatMediaNotFoundException) = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(ChatMediaNotReadyException::class)
    fun chatMediaNotReady(e: ChatMediaNotReadyException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(PremiumRequiredException::class)
    fun premiumRequired(e: PremiumRequiredException) = problem(HttpStatus.PAYMENT_REQUIRED, e.message)

    @ExceptionHandler(AlreadyPremiumException::class)
    fun alreadyPremium(e: AlreadyPremiumException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(InvalidStripeWebhookException::class)
    fun invalidStripeWebhook(e: InvalidStripeWebhookException) = problem(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(DuplicateNotificationPreferenceException::class)
    fun duplicateNotificationPreference(e: DuplicateNotificationPreferenceException) =
        problem(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(InvalidStorageKeyException::class)
    fun invalidStorageKey(e: InvalidStorageKeyException) = problem(HttpStatus.BAD_REQUEST, e.message)

    @ExceptionHandler(PhotoLimitReachedException::class)
    fun photoLimit(e: PhotoLimitReachedException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(PhotoNotFoundException::class)
    fun photoNotFound(e: PhotoNotFoundException) = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(UnsupportedImageTypeException::class)
    fun unsupportedImageType(e: UnsupportedImageTypeException) =
        problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.message)

    @ExceptionHandler(CannotReportSelfException::class)
    fun reportSelf(e: CannotReportSelfException) = problem(HttpStatus.UNPROCESSABLE_ENTITY, e.message)

    @ExceptionHandler(ReportTargetNotFoundException::class)
    fun reportTarget(e: ReportTargetNotFoundException) = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(DuplicateReportException::class)
    fun duplicateReport(e: DuplicateReportException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(AdminAccessDeniedException::class)
    fun adminAccessDenied(e: AdminAccessDeniedException) = problem(HttpStatus.FORBIDDEN, e.message)

    @ExceptionHandler(ReportNotFoundException::class)
    fun reportNotFound(e: ReportNotFoundException) = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(ReportNotOpenException::class)
    fun reportNotOpen(e: ReportNotOpenException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(ModerationTargetNotFoundException::class)
    fun moderationTarget(e: ModerationTargetNotFoundException) = problem(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(CannotModerateAdminException::class)
    fun moderateAdmin(e: CannotModerateAdminException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(UserAlreadyBannedException::class)
    fun alreadyBanned(e: UserAlreadyBannedException) = problem(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(UserNotBannedException::class)
    fun notBanned(e: UserNotBannedException) = problem(HttpStatus.CONFLICT, e.message)

    // Bean-validation failures on @RequestParam / @PathVariable arguments
    @ExceptionHandler(ConstraintViolationException::class)
    fun invalidParams(e: ConstraintViolationException) = problem(HttpStatus.BAD_REQUEST, e.message)

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
