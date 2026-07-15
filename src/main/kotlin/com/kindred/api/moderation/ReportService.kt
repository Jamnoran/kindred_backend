package com.kindred.api.moderation

import com.kindred.api.auth.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ReportService(
    private val users: UserRepository,
    private val reports: ReportRepository,
    private val events: ModerationEventRepository,
    private val clock: Clock,
) {

    /**
     * File a report against another user. One *open* report per reporter/reported
     * pair — re-reporting after a dismissal is allowed, spamming the queue is not.
     * Deleted targets 404 like nonexistent ones; banned targets stay reportable
     * (their old messages are still visible to matches).
     */
    @Transactional
    fun fileReport(reporterId: Long, reportedUserId: Long, reason: ReportReason, details: String?): Report {
        if (reporterId == reportedUserId) throw CannotReportSelfException()
        val target = users.findById(reportedUserId).orElseThrow { ReportTargetNotFoundException() }
        if (target.deletedAt != null) throw ReportTargetNotFoundException()
        if (reports.existsByReporterIdAndReportedUserIdAndStatus(reporterId, reportedUserId, ReportStatus.open)) {
            throw DuplicateReportException()
        }
        val report = reports.save(
            Report(
                reporterId = reporterId,
                reportedUserId = reportedUserId,
                reason = reason,
                details = details?.trim()?.ifEmpty { null },
                createdAt = clock.instant(),
            ),
        )
        events.save(
            ModerationEvent(
                actorUserId = reporterId,
                subjectUserId = reportedUserId,
                action = "report_filed",
                targetType = "report",
                targetId = report.id,
                detail = mapOf("reason" to reason.name),
                createdAt = clock.instant(),
            ),
        )
        return report
    }
}
