package com.kindred.api.moderation

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(AdminController::class)
@Import(SecurityConfig::class)
class AdminControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var adminService: AdminService

    private val admin = KindredUserDetails(id = 1L, email = "admin@example.com", passwordHash = "h", emailVerified = true)

    @Test
    fun `admin endpoints require authentication`() {
        mockMvc.perform(get("/api/v1/admin/reports")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `non-admins get 403`() {
        whenever(adminService.reportQueue(1L)).thenThrow(AdminAccessDeniedException())

        mockMvc.perform(get("/api/v1/admin/reports").with(user(admin))).andExpect(status().isForbidden)
    }

    @Test
    fun `report queue returns grouped summaries`() {
        val reportedAt = Instant.parse("2026-07-15T10:00:00Z")
        whenever(adminService.reportQueue(1L)).thenReturn(
            listOf(
                ReportedUserSummary(
                    userId = 20L,
                    email = "sam@example.com",
                    displayName = "Suspicious Sam",
                    banned = false,
                    openReportCount = 2,
                    totalReportCount = 5,
                    latestReportAt = reportedAt,
                    openReports = listOf(
                        AdminReportView(10L, 3L, ReportReason.bot, null, ReportStatus.open, reportedAt),
                    ),
                ),
            ),
        )

        mockMvc.perform(get("/api/v1/admin/reports").with(user(admin)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].userId").value(20))
            .andExpect(jsonPath("$[0].openReportCount").value(2))
            .andExpect(jsonPath("$[0].totalReportCount").value(5))
            .andExpect(jsonPath("$[0].openReports[0].reason").value("bot"))
    }

    @Test
    fun `ban returns 204`() {
        mockMvc.perform(post("/api/v1/admin/users/20/ban").with(user(admin)).with(csrf()))
            .andExpect(status().isNoContent)
        verify(adminService).banUser(1L, 20L)
    }

    @Test
    fun `banning an already banned user is 409`() {
        whenever(adminService.banUser(1L, 20L)).thenThrow(UserAlreadyBannedException())

        mockMvc.perform(post("/api/v1/admin/users/20/ban").with(user(admin)).with(csrf()))
            .andExpect(status().isConflict)
    }

    @Test
    fun `unban returns 204`() {
        mockMvc.perform(post("/api/v1/admin/users/20/unban").with(user(admin)).with(csrf()))
            .andExpect(status().isNoContent)
        verify(adminService).unbanUser(1L, 20L)
    }

    @Test
    fun `delete returns 204`() {
        mockMvc.perform(delete("/api/v1/admin/users/20").with(user(admin)).with(csrf()))
            .andExpect(status().isNoContent)
        verify(adminService).deleteUser(1L, 20L)
    }

    @Test
    fun `dismissing a report returns 204`() {
        mockMvc.perform(post("/api/v1/admin/reports/10/dismiss").with(user(admin)).with(csrf()))
            .andExpect(status().isNoContent)
        verify(adminService).dismissReport(1L, 10L)
    }

    @Test
    fun `dismissing an unknown report is 404`() {
        whenever(adminService.dismissReport(1L, 10L)).thenThrow(ReportNotFoundException())

        mockMvc.perform(post("/api/v1/admin/reports/10/dismiss").with(user(admin)).with(csrf()))
            .andExpect(status().isNotFound)
    }
}
