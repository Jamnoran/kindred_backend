package com.kindred.api.media

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.config.SecurityConfig
import org.junit.jupiter.api.Test
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

@WebMvcTest(MediaUploadController::class)
@Import(SecurityConfig::class)
class MediaUploadControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var mediaUploadService: MediaUploadService

    private val alice = KindredUserDetails(id = 1L, email = "alice@example.com", passwordHash = "h", emailVerified = true)

    @Test
    fun `requesting an upload requires authentication`() {
        mockMvc.perform(
            post("/api/v1/media/profile-photo-uploads").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"contentType":"image/jpeg"}"""),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `returns a presigned quarantine upload`() {
        whenever(mediaUploadService.presignProfilePhotoUpload(eq(1L), eq("image/jpeg"))).thenReturn(
            PresignedUpload(
                uploadUrl = "http://localhost:9000/kindred-media/quarantine/abc?X-Amz-Signature=sig",
                storageKey = "quarantine/abc",
                expiresAt = Instant.parse("2026-07-02T12:10:00Z"),
            ),
        )

        mockMvc.perform(
            post("/api/v1/media/profile-photo-uploads").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"contentType":"image/jpeg"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.storageKey").value("quarantine/abc"))
            .andExpect(jsonPath("$.uploadUrl").exists())
            .andExpect(jsonPath("$.expiresAt").exists())
    }

    @Test
    fun `unsupported content types are 415`() {
        whenever(mediaUploadService.presignProfilePhotoUpload(eq(1L), eq("image/gif")))
            .thenThrow(UnsupportedImageTypeException("image/gif"))

        mockMvc.perform(
            post("/api/v1/media/profile-photo-uploads").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"contentType":"image/gif"}"""),
        )
            .andExpect(status().isUnsupportedMediaType)
    }

    @Test
    fun `blank content type is a validation error`() {
        mockMvc.perform(
            post("/api/v1/media/profile-photo-uploads").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"contentType":""}"""),
        )
            .andExpect(status().isBadRequest)
    }
}
