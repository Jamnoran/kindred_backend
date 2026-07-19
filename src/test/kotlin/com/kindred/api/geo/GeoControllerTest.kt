package com.kindred.api.geo

import com.kindred.api.auth.KindredUserDetails
import com.kindred.api.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(GeoController::class)
@Import(SecurityConfig::class)
class GeoControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var cityIndex: CityIndex

    private val alice = KindredUserDetails(id = 1L, email = "alice@example.com", passwordHash = "h", emailVerified = true)

    @Test
    fun `city search requires authentication`() {
        mockMvc.perform(get("/api/v1/geo/cities?q=mal")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `city search returns matches with centroid coordinates`() {
        whenever(cityIndex.search("mal", 8)).thenReturn(
            listOf(City(id = 2692969, name = "Malmö", country = "SE", lat = 55.60587, lng = 13.00073, population = 301706)),
        )

        mockMvc.perform(get("/api/v1/geo/cities?q=mal").with(user(alice)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(2692969))
            .andExpect(jsonPath("$[0].name").value("Malmö"))
            .andExpect(jsonPath("$[0].country").value("SE"))
            .andExpect(jsonPath("$[0].lat").value(55.60587))
            .andExpect(jsonPath("$[0].lng").value(13.00073))
    }

    @Test
    fun `city search validates the limit`() {
        mockMvc.perform(get("/api/v1/geo/cities?q=mal&limit=999").with(user(alice)))
            .andExpect(status().isBadRequest)
    }
}
