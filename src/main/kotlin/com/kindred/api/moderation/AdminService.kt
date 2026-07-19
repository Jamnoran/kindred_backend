package com.kindred.api.moderation

import com.kindred.api.auth.User
import com.kindred.api.auth.UserRepository
import com.kindred.api.profile.ProfileRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Admin moderation: the report queue plus ban / unban / delete. Every entry point
 * re-checks `users.is_admin` from the DB (the session principal is created at login
 * and can go stale). Non-admins get 403 — the endpoints are in the public spec, so
 * there is nothing to hide.
 *
 * The session repository is an ObjectProvider for the same reason as ChatEventRelay:
 * slice tests and the `openapi` profile boot without Redis. Without it a banned
 * user's live session survives until it expires — login is still blocked.
 */
@Service
class AdminService(
    private val users: UserRepository,
    private val reports: ReportRepository,
    private val events: ModerationEventRepository,
    private val profiles: ProfileRepository,
    private val sessions: ObjectProvider<FindByIndexNameSessionRepository<*>>,
    private val clock: Clock,
) {

    /**
     * Open reports grouped per reported user, most-reported first — "multiple
     * reports" surfaces at the top without any threshold configuration.
     */
    @Transactional(readOnly = true)
    fun reportQueue(adminId: Long): List<ReportedUserSummary> {
        requireAdmin(adminId)
        val openByUser = reports.findAllByStatusOrderByCreatedAtAsc(ReportStatus.open).groupBy { it.reportedUserId }
        if (openByUser.isEmpty()) return emptyList()
        val totals = reports.countTotalsByReportedUserIds(openByUser.keys).associate { it.userId to it.total }
        val usersById = users.findAllById(openByUser.keys).associateBy { requireNotNull(it.id) }
        val namesById = profiles.findAllById(openByUser.keys).associate { it.userId to it.displayName }
        return openByUser.map { (userId, open) ->
            ReportedUserSummary(
                userId = userId,
                email = usersById[userId]?.email,
                displayName = namesById[userId],
                banned = usersById[userId]?.bannedAt != null,
                openReportCount = open.size,
                totalReportCount = totals[userId] ?: open.size.toLong(),
                latestReportAt = open.maxOf { it.createdAt },
                openReports = open.map(AdminReportView::from),
            )
        }.sortedWith(compareByDescending<ReportedUserSummary> { it.openReportCount }.thenByDescending { it.latestReportAt })
    }

    /** Close a report without acting on the user (false alarm, not actionable). */
    @Transactional
    fun dismissReport(adminId: Long, reportId: Long) {
        requireAdmin(adminId)
        val report = reports.findById(reportId).orElseThrow { ReportNotFoundException() }
        if (report.status != ReportStatus.open) throw ReportNotOpenException()
        report.status = ReportStatus.dismissed
        reports.save(report)
        logEvent(adminId, report.reportedUserId, "report_dismissed", reportId)
    }

    /**
     * Ban: blocks login, expires live sessions, hides the user from discovery,
     * and resolves their open reports. Reversible via [unbanUser].
     */
    @Transactional
    fun banUser(adminId: Long, userId: Long) {
        requireAdmin(adminId)
        val target = activeTarget(userId)
        if (target.bannedAt != null) throw UserAlreadyBannedException()
        target.bannedAt = clock.instant()
        users.save(target)
        val resolved = resolveOpenReports(userId)
        logEvent(adminId, userId, "user_banned", detail = mapOf("resolvedReports" to resolved))
        expireSessions(target)
    }

    @Transactional
    fun unbanUser(adminId: Long, userId: Long) {
        requireAdmin(adminId)
        val target = users.findById(userId).orElseThrow { ModerationTargetNotFoundException() }
        if (target.deletedAt != null) throw ModerationTargetNotFoundException()
        if (target.bannedAt == null) throw UserNotBannedException()
        target.bannedAt = null
        users.save(target)
        logEvent(adminId, userId, "user_unbanned")
    }

    /**
     * Soft-delete, same as self-deletion: the account behaves like it never existed
     * (login, discovery) and the Phase 4 GDPR erasure job hard-deletes rows + image
     * bytes later. Also resolves the user's open reports and expires their sessions.
     */
    @Transactional
    fun deleteUser(adminId: Long, userId: Long) {
        requireAdmin(adminId)
        val target = activeTarget(userId)
        target.deletedAt = clock.instant()
        users.save(target)
        val resolved = resolveOpenReports(userId)
        logEvent(adminId, userId, "user_deleted", detail = mapOf("resolvedReports" to resolved))
        expireSessions(target)
    }

    fun requireAdmin(userId: Long): User {
        val user = users.findById(userId).orElseThrow { AdminAccessDeniedException() }
        if (!user.isAdmin || user.deletedAt != null || user.bannedAt != null) throw AdminAccessDeniedException()
        return user
    }

    /** Ban/delete target: must exist, not be deleted, and not be an admin (no lockout via a rogue admin). */
    private fun activeTarget(userId: Long): User {
        val target = users.findById(userId).orElseThrow { ModerationTargetNotFoundException() }
        if (target.deletedAt != null) throw ModerationTargetNotFoundException()
        if (target.isAdmin) throw CannotModerateAdminException()
        return target
    }

    private fun resolveOpenReports(userId: Long): Int {
        val open = reports.findAllByReportedUserIdAndStatus(userId, ReportStatus.open)
        open.forEach { it.status = ReportStatus.resolved }
        reports.saveAll(open)
        return open.size
    }

    private fun logEvent(
        adminId: Long,
        subjectUserId: Long,
        action: String,
        reportId: Long? = null,
        detail: Map<String, Any?>? = null,
    ) {
        events.save(
            ModerationEvent(
                actorUserId = adminId,
                subjectUserId = subjectUserId,
                action = action,
                targetType = if (reportId != null) "report" else "user",
                targetId = reportId ?: subjectUserId,
                detail = detail,
                createdAt = clock.instant(),
            ),
        )
    }

    // Principal-name index = email (Spring Session indexed repository, see application.yml)
    private fun expireSessions(target: User) {
        val repo = sessions.ifAvailable ?: return
        repo.findByPrincipalName(target.email).keys.forEach(repo::deleteById)
    }
}
