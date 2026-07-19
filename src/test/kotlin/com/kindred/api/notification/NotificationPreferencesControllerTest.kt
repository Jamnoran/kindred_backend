package com.kindred.api.notification

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(NotificationPreferencesController::class)
@Import(SecurityConfig::class)
class NotificationPreferencesControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var preferenceService: NotificationPreferenceService

    private val alice = KindredUserDetails(id = 1L, email = "alice@example.com", passwordHash = "h", emailVerified = true)

    private val defaultMatrix = NotificationPreferencesResponse(
        listOf(
            NotificationPreferenceEntry(NotificationType.new_match, NotificationChannelType.email, enabled = true),
            NotificationPreferenceEntry(NotificationType.new_message, NotificationChannelType.email, enabled = false),
        ),
    )

    @Test
    fun `notification preferences require authentication`() {
        mockMvc.perform(get("/api/v1/notification-preferences")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `the full grid is returned`() {
        whenever(preferenceService.preferences(1L)).thenReturn(defaultMatrix)

        mockMvc.perform(get("/api/v1/notification-preferences").with(user(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.preferences[0].type").value("new_match"))
            .andExpect(jsonPath("$.preferences[0].channel").value("email"))
            .andExpect(jsonPath("$.preferences[0].enabled").value(true))
            .andExpect(jsonPath("$.preferences[1].type").value("new_message"))
            .andExpect(jsonPath("$.preferences[1].enabled").value(false))
    }

    @Test
    fun `put replaces the caller's preferences`() {
        whenever(preferenceService.replace(eq(1L), any())).thenReturn(defaultMatrix)

        mockMvc.perform(
            put("/api/v1/notification-preferences").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"preferences":[{"type":"new_message","channel":"email","enabled":false}]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.preferences[1].enabled").value(false))
    }

    @Test
    fun `duplicate pairs are a bad request`() {
        whenever(preferenceService.replace(eq(1L), any())).thenThrow(DuplicateNotificationPreferenceException())

        mockMvc.perform(
            put("/api/v1/notification-preferences").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"preferences":[
                      {"type":"new_message","channel":"email","enabled":false},
                      {"type":"new_message","channel":"email","enabled":true}
                    ]}
                    """.trimIndent(),
                ),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `an unknown channel is a bad request`() {
        mockMvc.perform(
            put("/api/v1/notification-preferences").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"preferences":[{"type":"new_message","channel":"carrier_pigeon","enabled":false}]}"""),
        ).andExpect(status().isBadRequest)
    }
}
