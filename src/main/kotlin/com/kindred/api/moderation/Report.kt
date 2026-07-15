package com.kindred.api.moderation

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/** Lowercase constants to match the JSON wire format (column is VARCHAR(64)). */
@Suppress("EnumEntryName")
enum class ReportReason { bot, catfish, inappropriate, underage, other }

/** Lowercase constants to match the MySQL ENUM values. */
@Suppress("EnumEntryName")
enum class ReportStatus { open, reviewing, resolved, dismissed }

@Entity
@Table(name = "reports")
class Report(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "reporter_id", nullable = false)
    var reporterId: Long,

    @Column(name = "reported_user_id", nullable = false)
    var reportedUserId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    var reason: ReportReason,

    @Column(columnDefinition = "TEXT")
    var details: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ReportStatus = ReportStatus.open,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
)

/** Per-user all-time report count for the admin queue. */
interface ReportCountView {
    val userId: Long
    val total: Long
}

interface ReportRepository : JpaRepository<Report, Long> {
    fun existsByReporterIdAndReportedUserIdAndStatus(
        reporterId: Long,
        reportedUserId: Long,
        status: ReportStatus,
    ): Boolean

    fun findAllByStatusOrderByCreatedAtAsc(status: ReportStatus): List<Report>

    fun findAllByReportedUserIdAndStatus(reportedUserId: Long, status: ReportStatus): List<Report>

    @Query(
        """
        select r.reportedUserId as userId, count(r) as total
        from Report r where r.reportedUserId in :userIds
        group by r.reportedUserId
        """,
    )
    fun countTotalsByReportedUserIds(@Param("userIds") userIds: Collection<Long>): List<ReportCountView>
}
