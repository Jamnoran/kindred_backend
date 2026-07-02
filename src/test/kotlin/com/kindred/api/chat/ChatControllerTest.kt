package com.kindred.api.chat

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.config.SecurityConfig
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
            MessageResponse(id = 100L, senderId = 1L, body = "hello", mediaId = null, createdAt = Instant.now(), readAt = null),
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
    fun `blank messages are rejected`() {
        whenever(chatService.send(1L, 7L, "   ", null)).thenThrow(EmptyMessageException())

        mockMvc.perform(
            post("/api/v1/conversations/7/messages").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"body":"   "}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `foreign conversations are 404`() {
        whenever(chatService.messages(1L, 7L, null, 50)).thenThrow(ConversationNotFoundException())

        mockMvc.perform(get("/api/v1/conversations/7/messages").with(user(alice)))
            .andExpect(status().isNotFound)
    }
}
