package com.kindred.api.profile

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

@WebMvcTest(ProfileController::class)
@Import(SecurityConfig::class)
class ProfileControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var profileService: ProfileService

    private val alice = KindredUserDetails(id = 1L, email = "alice@example.com", passwordHash = "h", emailVerified = true)

    private fun profile() = Profile(
        userId = 1L,
        displayName = "Alice",
        bio = "hello",
        gender = Gender.nonbinary,
        lookingFor = listOf("dating"),
        relationshipStyles = listOf(RelationshipStyle.polyamory, RelationshipStyle.non_monogamy),
        interests = mutableSetOf(Interest(id = 1L, slug = "hiking", label = "Hiking")),
    )

    @Test
    fun `profile endpoints require authentication`() {
        mockMvc.perform(get("/api/v1/profile")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/v1/interests")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/v1/profiles/nearby")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `get own profile returns it`() {
        whenever(profileService.getOwn(1L)).thenReturn(profile())

        mockMvc.perform(get("/api/v1/profile").with(user(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(1))
            .andExpect(jsonPath("$.displayName").value("Alice"))
            .andExpect(jsonPath("$.gender").value("nonbinary"))
            .andExpect(jsonPath("$.relationshipStyles[0]").value("polyamory"))
            .andExpect(jsonPath("$.relationshipStyles[1]").value("non_monogamy"))
            .andExpect(jsonPath("$.interests[0].slug").value("hiking"))
            .andExpect(jsonPath("$.locationSet").value(false))
    }

    @Test
    fun `get own profile is 404 before creation`() {
        whenever(profileService.getOwn(1L)).thenThrow(ProfileNotFoundException())

        mockMvc.perform(get("/api/v1/profile").with(user(alice)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `put profile upserts`() {
        whenever(profileService.upsert(eq(1L), any())).thenReturn(profile())

        mockMvc.perform(
            put("/api/v1/profile").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"displayName":"Alice","bio":"hello","gender":"nonbinary",
                       "lookingFor":["dating"],"relationshipStyles":["polyamory"],"interests":["hiking"]}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.displayName").value("Alice"))
            .andExpect(jsonPath("$.gender").value("nonbinary"))
    }

    @Test
    fun `put profile rejects unknown gender and relationship-style values`() {
        mockMvc.perform(
            put("/api/v1/profile").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Alice","gender":"not-a-valid-value"}"""),
        )
            .andExpect(status().isBadRequest)

        mockMvc.perform(
            put("/api/v1/profile").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Alice","relationshipStyles":["situationship"]}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `put profile validates the payload`() {
        mockMvc.perform(
            put("/api/v1/profile").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":""}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.displayName").exists())
    }

    @Test
    fun `put profile maps unknown interests to 400`() {
        whenever(profileService.upsert(eq(1L), any())).thenThrow(UnknownInterestException("flying"))

        mockMvc.perform(
            put("/api/v1/profile").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"displayName":"Alice","interests":["flying"]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("unknown interest: flying"))
    }

    @Test
    fun `put location updates coordinates`() {
        whenever(profileService.updateLocation(eq(1L), any())).thenReturn(
            profile().apply {
                locationSet = true
                locationLabel = "Stockholm"
            },
        )

        mockMvc.perform(
            put("/api/v1/profile/location").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"lat":59.33,"lng":18.07,"visibility":"exact"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.locationSet").value(true))
            .andExpect(jsonPath("$.locationLabel").value("Stockholm"))
    }

    @Test
    fun `put location accepts a visibility-only body`() {
        whenever(profileService.updateLocation(eq(1L), any())).thenReturn(profile())

        mockMvc.perform(
            put("/api/v1/profile/location").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"visibility":"hidden"}"""),
        )
            .andExpect(status().isOk)
    }

    @Test
    fun `put location with a lone coordinate is 400`() {
        whenever(profileService.updateLocation(eq(1L), any())).thenThrow(IncompleteCoordinatesException())

        mockMvc.perform(
            put("/api/v1/profile/location").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"lat":59.33}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `put location visibility-only without a stored location is 422`() {
        whenever(profileService.updateLocation(eq(1L), any())).thenThrow(VisibilityWithoutLocationException())

        mockMvc.perform(
            put("/api/v1/profile/location").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"visibility":"hidden"}"""),
        )
            .andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `put location rejects out-of-range coordinates`() {
        mockMvc.perform(
            put("/api/v1/profile/location").with(user(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"lat":91.0,"lng":18.07}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors.lat").exists())
    }

    @Test
    fun `nearby returns rounded distances`() {
        whenever(profileService.nearby(1L, 25)).thenReturn(
            listOf(
                object : NearbyProfileView {
                    override val userId = 2L
                    override val displayName = "Bea"
                    override val distanceMeters = 4211.0
                },
            ),
        )

        mockMvc.perform(get("/api/v1/profiles/nearby?radiusKm=25").with(user(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].userId").value(2))
            .andExpect(jsonPath("$[0].distanceKm").value(4))
    }

    @Test
    fun `nearby rejects an out-of-range radius`() {
        mockMvc.perform(get("/api/v1/profiles/nearby?radiusKm=9999").with(user(alice)))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `nearby without a stored location is 409`() {
        whenever(profileService.nearby(1L, 50)).thenThrow(LocationNotSetException())

        mockMvc.perform(get("/api/v1/profiles/nearby").with(user(alice)))
            .andExpect(status().isConflict)
    }

    @Test
    fun `interests lists the taxonomy`() {
        whenever(profileService.listInterests()).thenReturn(
            listOf(Interest(id = 2L, slug = "coffee", label = "Coffee"), Interest(id = 1L, slug = "hiking", label = "Hiking")),
        )

        mockMvc.perform(get("/api/v1/interests").with(user(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].slug").value("coffee"))
            .andExpect(jsonPath("$[1].label").value("Hiking"))
    }
}
