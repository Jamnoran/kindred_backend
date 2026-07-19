package com.kindred.api.moderation

import com.kindred.api.auth.User
import com.kindred.api.auth.UserRepository
import com.kindred.api.profile.Profile
import com.kindred.api.profile.ProfileRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.session.FindByIndexNameSessionRepository
import org.springframework.session.MapSession
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminServiceTest {

    private val users: UserRepository = mock()
    private val reports: ReportRepository = mock()
    private val events: ModerationEventRepository = mock()
    private val profiles: ProfileRepository = mock()
    private val sessionRepo: FindByIndexNameSessionRepository<MapSession> = mock()
    private val sessions: ObjectProvider<FindByIndexNameSessionRepository<*>> = mock {
        on { ifAvailable } doReturn sessionRepo
    }
    private val now = Instant.parse("2026-07-15T12:00:00Z")
    private val service = AdminService(users, reports, events, profiles, sessions, Clock.fixed(now, ZoneOffset.UTC))

    private fun user(
        id: Long,
        admin: Boolean = false,
        banned: Boolean = false,
        deleted: Boolean = false,
    ) = User(
        id = id,
        email = "user$id@example.com",
        passwordHash = "h",
        emailVerified = true,
        dob = LocalDate.of(1995, 1, 1),
        isAdmin = admin,
        bannedAt = if (banned) now.minusSeconds(3600) else null,
        deletedAt = if (deleted) now.minusSeconds(3600) else null,
    )

    private fun report(id: Long, reporterId: Long, reportedUserId: Long, createdAt: Instant = now) =
        Report(id = id, reporterId = reporterId, reportedUserId = reportedUserId, reason = ReportReason.bot, createdAt = createdAt)

    private fun givenAdmin(id: Long = 1L) {
        whenever(users.findById(id)).thenReturn(Optional.of(user(id, admin = true)))
    }

    @Test
    fun `non-admins are denied`() {
        whenever(users.findById(1L)).thenReturn(Optional.of(user(1L)))
        assertFailsWith<AdminAccessDeniedException> { service.reportQueue(1L) }
    }

    @Test
    fun `banned admins are denied`() {
        whenever(users.findById(1L)).thenReturn(Optional.of(user(1L, admin = true, banned = true)))
        assertFailsWith<AdminAccessDeniedException> { service.banUser(1L, 2L) }
    }

    @Test
    fun `report queue groups open reports per user, most reported first`() {
        givenAdmin()
        whenever(reports.findAllByStatusOrderByCreatedAtAsc(ReportStatus.open)).thenReturn(
            listOf(
                report(10L, reporterId = 3L, reportedUserId = 20L, createdAt = now.minusSeconds(300)),
                report(11L, reporterId = 4L, reportedUserId = 20L, createdAt = now.minusSeconds(100)),
                report(12L, reporterId = 3L, reportedUserId = 30L, createdAt = now.minusSeconds(50)),
            ),
        )
        whenever(reports.countTotalsByReportedUserIds(any())).thenReturn(
            listOf(
                object : ReportCountView { override val userId = 20L; override val total = 5L },
                object : ReportCountView { override val userId = 30L; override val total = 1L },
            ),
        )
        whenever(users.findAllById(any())).thenReturn(listOf(user(20L, banned = true), user(30L)))
        whenever(profiles.findAllById(any())).thenReturn(
            listOf(Profile(userId = 20L, displayName = "Suspicious Sam")),
        )

        val queue = service.reportQueue(1L)

        assertEquals(listOf(20L, 30L), queue.map { it.userId })
        val top = queue.first()
        assertEquals(2, top.openReportCount)
        assertEquals(5L, top.totalReportCount)
        assertEquals("user20@example.com", top.email)
        assertEquals("Suspicious Sam", top.displayName)
        assertTrue(top.banned)
        assertEquals(now.minusSeconds(100), top.latestReportAt)
        assertEquals(listOf(10L, 11L), top.openReports.map { it.id })
        assertNull(queue[1].displayName)
    }

    @Test
    fun `dismiss closes an open report and logs it`() {
        givenAdmin()
        val stored = report(10L, reporterId = 3L, reportedUserId = 20L)
        whenever(reports.findById(10L)).thenReturn(Optional.of(stored))

        service.dismissReport(1L, 10L)

        assertEquals(ReportStatus.dismissed, stored.status)
        val event = argumentCaptor<ModerationEvent>().apply { verify(events).save(capture()) }.firstValue
        assertEquals("report_dismissed", event.action)
        assertEquals(1L, event.actorUserId)
        assertEquals(20L, event.subjectUserId)
        assertEquals(10L, event.targetId)
    }

    @Test
    fun `dismissing a closed report is a conflict`() {
        givenAdmin()
        val stored = report(10L, reporterId = 3L, reportedUserId = 20L).apply { status = ReportStatus.resolved }
        whenever(reports.findById(10L)).thenReturn(Optional.of(stored))

        assertFailsWith<ReportNotOpenException> { service.dismissReport(1L, 10L) }
    }

    @Test
    fun `ban marks the user, resolves their open reports, and expires sessions`() {
        givenAdmin()
        val target = user(20L)
        whenever(users.findById(20L)).thenReturn(Optional.of(target))
        val open = listOf(report(10L, 3L, 20L), report(11L, 4L, 20L))
        whenever(reports.findAllByReportedUserIdAndStatus(20L, ReportStatus.open)).thenReturn(open)
        whenever(sessionRepo.findByPrincipalName("user20@example.com"))
            .thenReturn(mapOf("s1" to MapSession("s1"), "s2" to MapSession("s2")))

        service.banUser(1L, 20L)

        assertEquals(now, target.bannedAt)
        assertTrue(open.all { it.status == ReportStatus.resolved })
        verify(sessionRepo).deleteById("s1")
        verify(sessionRepo).deleteById("s2")
        val event = argumentCaptor<ModerationEvent>().apply { verify(events).save(capture()) }.firstValue
        assertEquals("user_banned", event.action)
        assertEquals(20L, event.subjectUserId)
        assertEquals(2, event.detail?.get("resolvedReports"))
    }

    @Test
    fun `ban still works when no session repository is available`() {
        val noSessions: ObjectProvider<FindByIndexNameSessionRepository<*>> = mock {
            on { ifAvailable } doReturn null
        }
        val svc = AdminService(users, reports, events, profiles, noSessions, Clock.fixed(now, ZoneOffset.UTC))
        givenAdmin()
        val target = user(20L)
        whenever(users.findById(20L)).thenReturn(Optional.of(target))
        whenever(reports.findAllByReportedUserIdAndStatus(20L, ReportStatus.open)).thenReturn(emptyList())

        svc.banUser(1L, 20L)

        assertNotNull(target.bannedAt)
    }

    @Test
    fun `banning an admin account is refused`() {
        givenAdmin()
        whenever(users.findById(20L)).thenReturn(Optional.of(user(20L, admin = true)))

        assertFailsWith<CannotModerateAdminException> { service.banUser(1L, 20L) }
    }

    @Test
    fun `banning an already banned user is a conflict`() {
        givenAdmin()
        whenever(users.findById(20L)).thenReturn(Optional.of(user(20L, banned = true)))

        assertFailsWith<UserAlreadyBannedException> { service.banUser(1L, 20L) }
    }

    @Test
    fun `banning an unknown or deleted user is not found`() {
        givenAdmin()
        whenever(users.findById(98L)).thenReturn(Optional.empty())
        whenever(users.findById(99L)).thenReturn(Optional.of(user(99L, deleted = true)))

        assertFailsWith<ModerationTargetNotFoundException> { service.banUser(1L, 98L) }
        assertFailsWith<ModerationTargetNotFoundException> { service.banUser(1L, 99L) }
    }

    @Test
    fun `unban clears the marker and logs it`() {
        givenAdmin()
        val target = user(20L, banned = true)
        whenever(users.findById(20L)).thenReturn(Optional.of(target))

        service.unbanUser(1L, 20L)

        assertNull(target.bannedAt)
        val event = argumentCaptor<ModerationEvent>().apply { verify(events).save(capture()) }.firstValue
        assertEquals("user_unbanned", event.action)
    }

    @Test
    fun `unbanning a user who is not banned is a conflict`() {
        givenAdmin()
        whenever(users.findById(20L)).thenReturn(Optional.of(user(20L)))

        assertFailsWith<UserNotBannedException> { service.unbanUser(1L, 20L) }
    }

    @Test
    fun `delete soft-deletes, resolves reports, and expires sessions`() {
        givenAdmin()
        val target = user(20L)
        whenever(users.findById(20L)).thenReturn(Optional.of(target))
        val open = listOf(report(10L, 3L, 20L))
        whenever(reports.findAllByReportedUserIdAndStatus(20L, ReportStatus.open)).thenReturn(open)
        whenever(sessionRepo.findByPrincipalName("user20@example.com")).thenReturn(mapOf("s1" to MapSession("s1")))

        service.deleteUser(1L, 20L)

        assertEquals(now, target.deletedAt)
        assertTrue(open.all { it.status == ReportStatus.resolved })
        verify(sessionRepo).deleteById("s1")
        val event = argumentCaptor<ModerationEvent>().apply { verify(events).save(capture()) }.firstValue
        assertEquals("user_deleted", event.action)
    }

    @Test
    fun `deleting an admin account is refused`() {
        givenAdmin()
        whenever(users.findById(20L)).thenReturn(Optional.of(user(20L, admin = true)))

        assertFailsWith<CannotModerateAdminException> { service.deleteUser(1L, 20L) }
        verify(users, never()).save(any())
    }
}
