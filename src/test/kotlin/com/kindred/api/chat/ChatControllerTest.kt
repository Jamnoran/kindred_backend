package com.kindred.api.chat

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.config.SecurityConfig
import com.kindred.api.media.PresignedUpload
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.PhotoUrls
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@WebMvcTest(ChatController::class)
@Import(SecurityConfig::class)
class ChatControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var chatService: ChatService

    @MockitoBean
    lateinit var chatMediaService: ChatMediaService

    private val alice = KindredUserDetails(id = 1L, email = "alice@example.com", passwordHash = "h", emailVerified = true)

    @Test
    fun `chat requires authentication`() {
        mockMvc.perform(get("/api/v1/conversations")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `send returns the created message`() {
        whenever(chatService.send(1L, 7L, "hello", null)).thenReturn(
            MessageResponse(id = 100L, senderId = 1L, body = "hello", media = null, createdAt = Instant.now(), readAt = null),
        )

        mockMvc.perform(
            post("/api/v1/conversations/7/messages").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"body":"hello"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(100))
            .andExpect(jsonPath("$.body").value("hello"))
    }

    @Test
    fun `image-only messages carry the pending media summary`() {
        val key = "quarantine/" + "ef".repeat(16)
        whenever(chatService.send(1L, 7L, null, key)).thenReturn(
            MessageResponse(
                id = 101L, senderId = 1L, body = null,
                media = ChatMediaSummary(id = 30L, status = ModerationStatus.pending, nsfw = false, blurhash = null),
                createdAt = Instant.now(), readAt = null,
            ),
        )

        mockMvc.perform(
            post("/api/v1/conversations/7/messages").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mediaStorageKey":"$key"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.media.id").value(30))
            .andExpect(jsonPath("$.media.status").value("pending"))
    }

    @Test
    fun `empty messages are rejected`() {
        whenever(chatService.send(1L, 7L, "   ", null)).thenThrow(EmptyMessageException())

        mockMvc.perform(
            post("/api/v1/conversations/7/messages").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"body":"   "}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `media upload presign is scoped to the conversation`() {
        whenever(chatMediaService.presignUpload(1L, 7L, "image/jpeg")).thenReturn(
            PresignedUpload(uploadUrl = "http://minio/put", storageKey = "quarantine/abc", expiresAt = Instant.now()),
        )

        mockMvc.perform(
            post("/api/v1/conversations/7/media-uploads").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"contentType":"image/jpeg"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.uploadUrl").value("http://minio/put"))
            .andExpect(jsonPath("$.storageKey").value("quarantine/abc"))
    }

    @Test
    fun `signed media urls come back with an expiry`() {
        whenever(chatMediaService.signedUrls(1L, 7L, 30L)).thenReturn(
            ChatMediaUrlsResponse(
                mediaId = 30L,
                urls = PhotoUrls(thumb = "http://signed/t", card = "http://signed/c", full = "http://signed/f"),
                expiresAt = Instant.parse("2026-07-03T12:05:00Z"),
            ),
        )

        mockMvc.perform(get("/api/v1/conversations/7/media/30").with(user(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mediaId").value(30))
            .andExpect(jsonPath("$.urls.full").value("http://signed/f"))
            .andExpect(jsonPath("$.expiresAt").value("2026-07-03T12:05:00Z"))
    }

    @Test
    fun `still-processing media is a conflict`() {
        whenever(chatMediaService.signedUrls(1L, 7L, 30L)).thenThrow(ChatMediaNotReadyException())

        mockMvc.perform(get("/api/v1/conversations/7/media/30").with(user(alice)))
            .andExpect(status().isConflict)
    }

    @Test
    fun `foreign conversations are 404`() {
        whenever(chatService.messages(1L, 7L, null, 50)).thenThrow(ConversationNotFoundException())

        mockMvc.perform(get("/api/v1/conversations/7/messages").with(user(alice)))
            .andExpect(status().isNotFound)
    }
}
