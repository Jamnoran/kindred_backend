package com.kindred.api.profile

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
    private val now = Instant.parse("2026-07-02T12:00:00Z")
    private val service = ProfileService(profiles, interests, Clock.fixed(now, ZoneOffset.UTC))

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
                lookingFor = listOf("Friends ", "dating"),
                interests = listOf("Hiking", "coffee"),
            ),
        )

        assertEquals("Alice", profile.displayName)
        assertEquals("hello", profile.bio)
        assertEquals(listOf("friends", "dating"), profile.lookingFor)
        assertEquals(setOf("hiking", "coffee"), profile.interests.map { it.slug }.toSet())
        assertEquals(now, profile.lastActiveAt)
    }

    @Test
    fun `upsert has PUT semantics - omitted fields are cleared`() {
        val existing = Profile(userId = 1L, displayName = "Old", interests = mutableSetOf(hiking))
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(existing)
        whenever(profiles.save(any())).thenAnswer { it.arguments[0] }

        val profile = service.upsert(1L, UpdateProfileRequest(displayName = "New", bio = " "))

        assertEquals("New", profile.displayName)
        assertNull(profile.bio) // blank bio normalized to null
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
    fun `updateLocation persists visibility and runs the spatial update`() {
        val existing = Profile(userId = 1L, displayName = "Alice")
        whenever(profiles.findById(1L)).thenReturn(Optional.of(existing))
        whenever(profiles.findWithInterestsByUserId(1L)).thenReturn(existing)
        whenever(profiles.save(any())).thenAnswer { it.arguments[0] }

        service.updateLocation(1L, UpdateLocationRequest(lat = 59.33, lng = 18.07, visibility = LocationVisibility.exact))

        verify(profiles).updateLocation(1L, 59.33, 18.07)
        val saved = argumentCaptor<Profile>().apply { verify(profiles).save(capture()) }.firstValue
        assertEquals(LocationVisibility.exact, saved.locationVisibility)
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
