package com.kindred.api.moderation

import jakarta.validation.constraints.Size
import java.time.Instant

// --- user-facing reporting ---

data class ReportRequest(
    val reason: ReportReason,
    @field:Size(max = 2000, message = "details must be at most 2000 characters")
    val details: String? = null,
)

data class ReportResponse(
    val id: Long,
    val reportedUserId: Long,
    val reason: ReportReason,
    val status: ReportStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(report: Report) = ReportResponse(
            id = requireNotNull(report.id),
            reportedUserId = report.reportedUserId,
            reason = report.reason,
            status = report.status,
            createdAt = report.createdAt,
        )
    }
}

// --- admin queue ---

data class AdminReportView(
    val id: Long,
    val reporterId: Long,
    val reason: ReportReason,
    val details: String?,
    val status: ReportStatus,
    val createdAt: Instant,
) {
    companion object {
        fun from(report: Report) = AdminReportView(
            id = requireNotNull(report.id),
            reporterId = report.reporterId,
            reason = report.reason,
            details = report.details,
            status = report.status,
            createdAt = report.createdAt,
        )
    }
}

/** One queue entry per reported user — most open reports first. */
data class ReportedUserSummary(
    val userId: Long,
    val email: String?,
    val displayName: String?,
    val banned: Boolean,
    val openReportCount: Int,
    val totalReportCount: Long,
    val latestReportAt: Instant,
    val openReports: List<AdminReportView>,
)

// --- domain exceptions (mapped in common/ApiExceptionHandler) ---

class CannotReportSelfException : RuntimeException("you cannot report yourself")

class ReportTargetNotFoundException : RuntimeException("user not found")

class DuplicateReportException : RuntimeException("you already have an open report on this user")

class AdminAccessDeniedException : RuntimeException("admin access required")

class ReportNotFoundException : RuntimeException("report not found")

class ReportNotOpenException : RuntimeException("report is not open")

class ModerationTargetNotFoundException : RuntimeException("user not found")

class CannotModerateAdminException : RuntimeException("admin accounts cannot be banned or deleted")

class UserAlreadyBannedException : RuntimeException("user is already banned")

class UserNotBannedException : RuntimeException("user is not banned")
