package com.kindred.api.profile

import com.kindred.api.geo.City
import com.kindred.api.geo.CityIndex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileServiceTest {

    private val profiles: ProfileRepository = mock()
    private val interests: InterestRepository = mock()
    private val cityIndex: CityIndex = mock()
    private val now = Instant.parse("2026-07-02T12:00:00Z")
    private val service = ProfileService(profiles, interests, cityIndex, Clock.fixed(now, ZoneOffset.UTC))

    private val hiking = Interest(id = 1L, slug = "hiking", label = "Hiking")
    private val coffee = Interest(id = 2L, slug = "coffee", label = "Coffee")

    @Test
    fun `getOwn throws when no profile exists yet`() {
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(null)

        assertThrows<ProfileNotFoundException> { service.getOwn(1L) }
    }

    @Test
    fun `upsert creates a profile with resolved interests`() {
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(null)
        whenever(interests.findBySlugIn(listOf("hiking", "coffee"))).thenReturn(listOf(hiking, coffee))
        whenever(profiles.save(any())).thenAnswer { it.arguments[0] }

        val profile = service.upsert(
            1L,
            UpdateProfileRequest(
                displayName = "Alice",
                bio = "hello",
                gender = Gender.nonbinary,
                lookingFor = listOf("Friends ", "dating"),
                interests = listOf("Hiking", "coffee"),
            ),
        )

        assertEquals("Alice", profile.displayName)
        assertEquals("hello", profile.bio)
        assertEquals(Gender.nonbinary, profile.gender)
        assertEquals(listOf("friends", "dating"), profile.lookingFor)
        assertEquals(setOf("hiking", "coffee"), profile.interests.map { it.slug }.toSet())
        assertEquals(now, profile.lastActiveAt)
    }

    @Test
    fun `upsert expands the non-monogamy umbrella on relationship styles`() {
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(null)
        whenever(profiles.save(any())).thenAnswer { it.arguments[0] }

        val poly = service.upsert(
            1L,
            UpdateProfileRequest(displayName = "Alice", relationshipStyles = listOf(RelationshipStyle.polyamory)),
        )
        assertEquals(listOf(RelationshipStyle.polyamory, RelationshipStyle.non_monogamy), poly.relationshipStyles)

        val mono = service.upsert(
            1L,
            UpdateProfileRequest(displayName = "Alice", relationshipStyles = listOf(RelationshipStyle.monogamy)),
        )
        assertEquals(listOf(RelationshipStyle.monogamy), mono.relationshipStyles)
    }

    @Test
    fun `upsert has PUT semantics - omitted fields are cleared`() {
        val existing = Profile(
            userId = 1L,
            displayName = "Old",
            gender = Gender.woman,
            relationshipStyles = listOf(RelationshipStyle.monogamy),
            interests = mutableSetOf(hiking),
        )
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(existing)
        whenever(profiles.save(any())).thenAnswer { it.arguments[0] }

        val profile = service.upsert(1L, UpdateProfileRequest(displayName = "New", bio = " "))

        assertEquals("New", profile.displayName)
        assertNull(profile.bio) // blank bio normalized to null
        assertNull(profile.gender)
        assertNull(profile.relationshipStyles)
        assertEquals(emptySet(), profile.interests.map { it.slug }.toSet())
        verify(interests, never()).findBySlugIn(any())
    }

    @Test
    fun `upsert rejects unknown interest slugs`() {
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(null)
        whenever(interests.findBySlugIn(listOf("hiking", "flying"))).thenReturn(listOf(hiking))

        assertThrows<UnknownInterestException> {
            service.upsert(1L, UpdateProfileRequest(displayName = "Alice", interests = listOf("hiking", "flying")))
        }
        verify(profiles, never()).save(any())
    }

    @Test
    fun `updateLocation persists visibility, derives the label, and runs the spatial update`() {
        val existing = Profile(userId = 1L, displayName = "Alice")
        whenever(profiles.findById(1L)).thenReturn(Optional.of(existing))
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(existing)
        whenever(profiles.save(any())).thenAnswer { it.arguments[0] }
        whenever(cityIndex.nearest(59.33, 18.07))
            .thenReturn(City(id = 2673730, name = "Stockholm", country = "SE", lat = 59.32938, lng = 18.06871, population = 1_515_017))

        service.updateLocation(1L, UpdateLocationRequest(lat = 59.33, lng = 18.07, visibility = LocationVisibility.exact))

        // exact visibility → coordinates stored precisely, no grid snapping
        verify(profiles).updateLocation(1L, 59.33, 18.07)
        val saved = argumentCaptor<Profile>().apply { verify(profiles).save(capture()) }.firstValue
        assertEquals(LocationVisibility.exact, saved.locationVisibility)
        assertEquals("Stockholm", saved.locationLabel)
    }

    @Test
    fun `updateLocation snaps stored coordinates to the ~5km grid unless visibility is exact`() {
        val existing = Profile(userId = 1L, displayName = "Alice") // default visibility: approximate
        whenever(profiles.findById(1L)).thenReturn(Optional.of(existing))
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(existing)
        whenever(profiles.save(any())).thenAnswer { it.arguments[0] }

        service.updateLocation(1L, UpdateLocationRequest(lat = 55.60587, lng = 13.00073))

        val lat = argumentCaptor<Double>()
        val lng = argumentCaptor<Double>()
        verify(profiles).updateLocation(eq(1L), lat.capture(), lng.capture())
        // label is derived from the precise fix, storage gets the snapped one
        verify(cityIndex).nearest(55.60587, 13.00073)
        // on the 0.045° latitude grid (modulo float error), within one cell of the original
        assertEquals(Math.round(lat.firstValue / 0.045) * 0.045, lat.firstValue, 1e-9)
        assertEquals(55.60587, lat.firstValue, 0.045)
        assertEquals(13.00073, lng.firstValue, 0.09)
    }

    @Test
    fun `updateLocation with visibility only keeps the stored coordinates and label`() {
        val existing = Profile(userId = 1L, displayName = "Alice", locationSet = true, locationLabel = "Malmö")
        whenever(profiles.findById(1L)).thenReturn(Optional.of(existing))
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(existing)
        whenever(profiles.save(any())).thenAnswer { it.arguments[0] }

        service.updateLocation(1L, UpdateLocationRequest(visibility = LocationVisibility.hidden))

        verify(profiles, never()).updateLocation(any(), any(), any())
        val saved = argumentCaptor<Profile>().apply { verify(profiles).save(capture()) }.firstValue
        assertEquals(LocationVisibility.hidden, saved.locationVisibility)
        assertEquals("Malmö", saved.locationLabel)
    }

    @Test
    fun `updateLocation with visibility only is rejected before any location was stored`() {
        val existing = Profile(userId = 1L, displayName = "Alice", locationSet = false)
        whenever(profiles.findById(1L)).thenReturn(Optional.of(existing))

        assertThrows<VisibilityWithoutLocationException> {
            service.updateLocation(1L, UpdateLocationRequest(visibility = LocationVisibility.exact))
        }
        verify(profiles, never()).save(any())
    }

    @Test
    fun `updateLocation rejects a lone coordinate`() {
        assertThrows<IncompleteCoordinatesException> {
            service.updateLocation(1L, UpdateLocationRequest(lat = 55.6))
        }
        assertThrows<IncompleteCoordinatesException> {
            service.updateLocation(1L, UpdateLocationRequest(lng = 13.0))
        }
        verify(profiles, never()).save(any())
    }

    @Test
    fun `updateLocation requires an existing profile`() {
        whenever(profiles.findById(1L)).thenReturn(Optional.empty())

        assertThrows<ProfileNotFoundException> {
            service.updateLocation(1L, UpdateLocationRequest(lat = 0.0, lng = 0.0))
        }
        verify(profiles, never()).updateLocation(any(), any(), any())
    }

    @Test
    fun `nearby requires the caller to have set a location`() {
        val own = Profile(userId = 1L, displayName = "Alice", locationSet = false)
        whenever(profiles.findById(1L)).thenReturn(Optional.of(own))

        assertThrows<LocationNotSetException> { service.nearby(1L, 50) }
    }

    @Test
    fun `nearby queries with the radius converted to meters`() {
        val own = Profile(userId = 1L, displayName = "Alice", locationSet = true)
        whenever(profiles.findById(1L)).thenReturn(Optional.of(own))
        whenever(profiles.findNearby(eq(1L), eq(25_000.0))).thenReturn(emptyList())

        service.nearby(1L, 25)

        verify(profiles).findNearby(1L, 25_000.0)
    }
}
