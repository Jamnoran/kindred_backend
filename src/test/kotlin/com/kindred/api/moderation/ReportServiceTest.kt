package com.kindred.api.moderation

import com.kindred.api.auth.User
import com.kindred.api.auth.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReportServiceTest {

    private val users: UserRepository = mock()
    private val reports: ReportRepository = mock {
        on { save(any<Report>()) } doAnswer { (it.arguments[0] as Report).apply { id = id ?: 42L } }
    }
    private val events: ModerationEventRepository = mock()
    private val now = Instant.parse("2026-07-15T12:00:00Z")
    private val service = ReportService(users, reports, events, Clock.fixed(now, ZoneOffset.UTC))

    private fun user(id: Long, deleted: Boolean = false) = User(
        id = id,
        email = "user$id@example.com",
        passwordHash = "h",
        emailVerified = true,
        dob = LocalDate.of(1995, 1, 1),
        deletedAt = if (deleted) now else null,
    )

    @Test
    fun `files a report and logs a moderation event`() {
        whenever(users.findById(2L)).thenReturn(Optional.of(user(2L)))

        val report = service.fileReport(1L, 2L, ReportReason.bot, "sends the same message to everyone")

        assertEquals(1L, report.reporterId)
        assertEquals(2L, report.reportedUserId)
        assertEquals(ReportReason.bot, report.reason)
        assertEquals(ReportStatus.open, report.status)
        assertEquals(now, report.createdAt)

        val event = argumentCaptor<ModerationEvent>().apply { verify(events).save(capture()) }.firstValue
        assertEquals("report_filed", event.action)
        assertEquals(1L, event.actorUserId)
        assertEquals(2L, event.subjectUserId)
        assertEquals("report", event.targetType)
        assertEquals(42L, event.targetId)
        assertEquals("bot", event.detail?.get("reason"))
    }

    @Test
    fun `blank details are stored as null`() {
        whenever(users.findById(2L)).thenReturn(Optional.of(user(2L)))

        val report = service.fileReport(1L, 2L, ReportReason.catfish, "   ")

        assertNull(report.details)
    }

    @Test
    fun `reporting yourself is rejected`() {
        assertFailsWith<CannotReportSelfException> { service.fileReport(1L, 1L, ReportReason.other, null) }
    }

    @Test
    fun `reporting an unknown user is not found`() {
        whenever(users.findById(99L)).thenReturn(Optional.empty())

        assertFailsWith<ReportTargetNotFoundException> { service.fileReport(1L, 99L, ReportReason.bot, null) }
    }

    @Test
    fun `reporting a deleted user behaves like an unknown one`() {
        whenever(users.findById(2L)).thenReturn(Optional.of(user(2L, deleted = true)))

        assertFailsWith<ReportTargetNotFoundException> { service.fileReport(1L, 2L, ReportReason.bot, null) }
    }

    @Test
    fun `a second open report on the same user is a conflict`() {
        whenever(users.findById(2L)).thenReturn(Optional.of(user(2L)))
        whenever(reports.existsByReporterIdAndReportedUserIdAndStatus(1L, 2L, ReportStatus.open)).thenReturn(true)

        assertFailsWith<DuplicateReportException> { service.fileReport(1L, 2L, ReportReason.inappropriate, null) }
    }
}
