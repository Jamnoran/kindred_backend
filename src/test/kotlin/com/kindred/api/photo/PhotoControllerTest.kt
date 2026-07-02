package com.kindred.api.photo

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(PhotoController::class)
@Import(SecurityConfig::class)
class PhotoControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var photoService: PhotoService

    private val alice = KindredUserDetails(id = 1L, email = "alice@example.com", passwordHash = "h", emailVerified = true)
    private val validKey = "quarantine/" + "ab".repeat(16)

    @Test
    fun `photo endpoints require authentication`() {
        mockMvc.perform(get("/api/v1/photos")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `submit returns 202 with the pending photo`() {
        whenever(photoService.submit(1L, validKey)).thenReturn(
            Photo(id = 42L, profileUserId = 1L, storageKey = validKey),
        )

        mockMvc.perform(
            post("/api/v1/photos").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"storageKey":"$validKey"}"""),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").value(42))
            .andExpect(jsonPath("$.status").value("pending"))
            .andExpect(jsonPath("$.urls").doesNotExist())
    }

    @Test
    fun `bad storage keys are 400`() {
        whenever(photoService.submit(eq(1L), eq("nope"))).thenThrow(InvalidStorageKeyException("bad key"))

        mockMvc.perform(
            post("/api/v1/photos").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"storageKey":"nope"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `photo limit maps to 409`() {
        whenever(photoService.submit(eq(1L), eq(validKey))).thenThrow(PhotoLimitReachedException(6))

        mockMvc.perform(
            post("/api/v1/photos").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"storageKey":"$validKey"}"""),
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun `list includes CDN urls only for approved photos`() {
        whenever(photoService.listOwn(1L)).thenReturn(
            listOf(
                Photo(
                    id = 1L, profileUserId = 1L, storageKey = "profiles/aabb", isPrimary = true,
                    moderationStatus = ModerationStatus.approved, blurhash = "LKO2?U%2Tw=w]~RB",
                ),
                Photo(id = 2L, profileUserId = 1L, storageKey = validKey, sortOrder = 1),
            ),
        )

        mockMvc.perform(get("/api/v1/photos").with(user(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].status").value("approved"))
            .andExpect(jsonPath("$[0].urls.thumb").value("http://localhost:9000/kindred-media/profiles/aabb/thumb.jpg"))
            .andExpect(jsonPath("$[0].blurhash").exists())
            .andExpect(jsonPath("$[1].status").value("pending"))
            .andExpect(jsonPath("$[1].urls").doesNotExist())
    }

    @Test
    fun `delete returns 204`() {
        mockMvc.perform(delete("/api/v1/photos/42").with(user(alice)).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `deleting a missing photo is 404`() {
        whenever(photoService.delete(1L, 42L)).thenThrow(PhotoNotFoundException())

        mockMvc.perform(delete("/api/v1/photos/42").with(user(alice)).with(csrf()))
            .andExpect(status().isNotFound)
    }
}
