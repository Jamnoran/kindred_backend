package com.kindred.api.discovery

import com.kindred.api.profile.Gender
import com.kindred.api.profile.RelationshipStyle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import kotlin.test.assertEquals

class PreferencesServiceTest {

    private val repository: PreferencesRepository = mock()
    private val service = PreferencesService(repository)

    @Test
    fun `get falls back to defaults when nothing is stored`() {
        whenever(repository.findById(1L)).thenReturn(Optional.empty())

        val prefs = service.get(1L)

        assertEquals(50, prefs.distanceKm)
        assertEquals(null, prefs.genders)
        assertEquals(null, prefs.relationshipStyles)
    }

    @Test
    fun `update stores gender and relationship-style filters verbatim - no umbrella expansion`() {
        whenever(repository.findById(1L)).thenReturn(Optional.empty())
        whenever(repository.save(any())).thenAnswer { it.arguments[0] }

        val prefs = service.update(
            1L,
            UpdatePreferencesRequest(
                genders = listOf(Gender.woman, Gender.nonbinary, Gender.woman),
                relationshipStyles = listOf(RelationshipStyle.polyamory),
            ),
        )

        assertEquals(listOf(Gender.woman, Gender.nonbinary), prefs.genders)
        // a "polyamory" filter must keep meaning specifically poly
        assertEquals(listOf(RelationshipStyle.polyamory), prefs.relationshipStyles)
    }

    @Test
    fun `update rejects an inverted age window`() {
        assertThrows<ResponseStatusException> {
            service.update(1L, UpdatePreferencesRequest(ageMin = 40, ageMax = 30))
        }
    }
}
