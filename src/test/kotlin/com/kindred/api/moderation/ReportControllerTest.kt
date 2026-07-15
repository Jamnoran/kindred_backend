package com.kindred.api.moderation

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(ReportController::class)
@Import(SecurityConfig::class)
class ReportControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var reportService: ReportService

    private val alice = KindredUserDetails(id = 1L, email = "alice@example.com", passwordHash = "h", emailVerified = true)

    @Test
    fun `reporting requires authentication`() {
        mockMvc.perform(
            post("/api/v1/users/2/report").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"bot"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `filing a report returns 201 with the stored report`() {
        whenever(reportService.fileReport(1L, 2L, ReportReason.catfish, "photos are stolen")).thenReturn(
            Report(
                id = 42L,
                reporterId = 1L,
                reportedUserId = 2L,
                reason = ReportReason.catfish,
                details = "photos are stolen",
                createdAt = Instant.parse("2026-07-15T12:00:00Z"),
            ),
        )

        mockMvc.perform(
            post("/api/v1/users/2/report").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"catfish","details":"photos are stolen"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.reportedUserId").value(2))
            .andExpect(jsonPath("$.reason").value("catfish"))
            .andExpect(jsonPath("$.status").value("open"))
    }

    @Test
    fun `an unknown reason is a 400`() {
        mockMvc.perform(
            post("/api/v1/users/2/report").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"ugly-profile"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `a duplicate open report maps to 409`() {
        whenever(reportService.fileReport(eq(1L), eq(2L), any(), anyOrNull())).thenThrow(DuplicateReportException())

        mockMvc.perform(
            post("/api/v1/users/2/report").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"bot"}"""),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `reporting an unknown user maps to 404`() {
        whenever(reportService.fileReport(eq(1L), eq(99L), any(), anyOrNull())).thenThrow(ReportTargetNotFoundException())

        mockMvc.perform(
            post("/api/v1/users/99/report").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"bot"}"""),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `reporting yourself maps to 422`() {
        whenever(reportService.fileReport(eq(1L), eq(1L), any(), anyOrNull())).thenThrow(CannotReportSelfException())

        mockMvc.perform(
            post("/api/v1/users/1/report").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"other"}"""),
        ).andExpect(status().isUnprocessableEntity)
    }
}
