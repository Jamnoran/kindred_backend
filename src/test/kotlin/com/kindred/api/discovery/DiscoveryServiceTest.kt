package com.kindred.api.discovery

import com.kindred.api.photo.PhotoRepository
import com.kindred.api.profile.Gender
import com.kindred.api.profile.Profile
import com.kindred.api.profile.ProfileRepository
import com.kindred.api.profile.RelationshipStyle
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

/**
 * Exercises the JSON hard filters applied in DiscoveryService on top of the SQL
 * candidate query — most importantly that the gender filter is enforced MUTUALLY.
 */
class DiscoveryServiceTest {

    private val candidates: CandidateRepository = mock()
    private val profiles: ProfileRepository = mock()
    private val photos: PhotoRepository = mock()
    private val preferencesService: PreferencesService = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val userAges: UserAgeLookup = mock()
    private val now = Instant.parse("2026-07-09T12:00:00Z")
    private val service = DiscoveryService(
        candidates, profiles, photos, preferencesService, preferencesRepository, userAges,
        Clock.fixed(now, ZoneOffset.UTC), "http://cdn.test",
    )

    private val viewerId = 1L

    private fun row(userId: Long) = object : CandidateView {
        override val userId = userId
        override val age = 30
        override val distanceMeters: Double? = null
        override val lastActiveAt = Timestamp.from(now)
    }

    private fun setup(
        viewer: Profile,
        viewerPrefs: Preferences,
        candidateProfiles: List<Profile>,
        candidatePrefs: List<Preferences> = emptyList(),
    ) {
        whenever(profiles.findWithInterestsByUserId(viewerId)).thenReturn(viewer)
        whenever(preferencesService.get(viewerId)).thenReturn(viewerPrefs)
        whenever(userAges.ageOf(viewerId)).thenReturn(30)
        whenever(candidates.findCandidates(any(), any(), any(), any()))
            .thenReturn(candidateProfiles.map { row(it.userId) })
        whenever(profiles.findAllWithInterestsByUserIdIn(any())).thenReturn(candidateProfiles)
        whenever(preferencesRepository.findAllById(any())).thenReturn(candidatePrefs)
    }

    private fun discoveredIds() = service.discover(viewerId, 50).map { it.userId }

    @Test
    fun `gender filter hides candidates outside the viewer's shown genders, including unspecified`() {
        setup(
            viewer = Profile(userId = viewerId, displayName = "Alex", gender = Gender.man),
            viewerPrefs = Preferences(userId = viewerId, genders = listOf(Gender.woman, Gender.nonbinary)),
            candidateProfiles = listOf(
                Profile(userId = 2L, displayName = "Bea", gender = Gender.woman),
                Profile(userId = 3L, displayName = "Carl", gender = Gender.man),
                Profile(userId = 4L, displayName = "Drew", gender = Gender.nonbinary),
                Profile(userId = 5L, displayName = "Eli", gender = null),
            ),
        )

        assertEquals(listOf(2L, 4L), discoveredIds())
    }

    @Test
    fun `gender filter is mutual - candidates whose own filter excludes the viewer are hidden`() {
        setup(
            viewer = Profile(userId = viewerId, displayName = "Alex", gender = Gender.man),
            viewerPrefs = Preferences(userId = viewerId, genders = listOf(Gender.woman)),
            candidateProfiles = listOf(
                Profile(userId = 2L, displayName = "Bea", gender = Gender.woman),
                Profile(userId = 3L, displayName = "Cleo", gender = Gender.woman),
                Profile(userId = 4L, displayName = "Dana", gender = Gender.woman),
            ),
            candidatePrefs = listOf(
                Preferences(userId = 2L, genders = listOf(Gender.man)), // accepts the viewer
                Preferences(userId = 3L, genders = listOf(Gender.woman)), // excludes him
                // Dana has no preferences row → open to everyone
            ),
        )

        assertEquals(listOf(2L, 4L), discoveredIds())
    }

    @Test
    fun `unset gender filters mean everyone is visible, whatever their gender`() {
        setup(
            viewer = Profile(userId = viewerId, displayName = "Alex", gender = null),
            viewerPrefs = Preferences(userId = viewerId),
            candidateProfiles = listOf(
                Profile(userId = 2L, displayName = "Bea", gender = Gender.woman),
                Profile(userId = 3L, displayName = "Carl", gender = Gender.man),
                Profile(userId = 4L, displayName = "Eli", gender = null),
            ),
        )

        assertEquals(listOf(2L, 3L, 4L), discoveredIds())
    }

    @Test
    fun `relationship-style filter only excludes candidates who declared non-overlapping styles`() {
        setup(
            viewer = Profile(
                userId = viewerId,
                displayName = "Alex",
                relationshipStyles = listOf(RelationshipStyle.polyamory, RelationshipStyle.non_monogamy),
            ),
            viewerPrefs = Preferences(userId = viewerId, relationshipStyles = listOf(RelationshipStyle.non_monogamy)),
            candidateProfiles = listOf(
                // umbrella-normalized open profile — matches a non_monogamy filter
                Profile(
                    userId = 2L,
                    displayName = "Bea",
                    relationshipStyles = listOf(RelationshipStyle.open, RelationshipStyle.non_monogamy),
                ),
                Profile(userId = 3L, displayName = "Carl", relationshipStyles = listOf(RelationshipStyle.monogamy)),
                Profile(userId = 4L, displayName = "Drew", relationshipStyles = null), // undeclared → not filtered
            ),
        )

        assertEquals(listOf(2L, 4L), discoveredIds())
    }

    @Test
    fun `cards carry gender and relationship styles`() {
        setup(
            viewer = Profile(userId = viewerId, displayName = "Alex"),
            viewerPrefs = Preferences(userId = viewerId),
            candidateProfiles = listOf(
                Profile(
                    userId = 2L,
                    displayName = "Bea",
                    gender = Gender.woman,
                    relationshipStyles = listOf(RelationshipStyle.polyamory, RelationshipStyle.non_monogamy),
                ),
            ),
        )

        val card = service.discover(viewerId, 50).single()
        assertEquals(Gender.woman, card.gender)
        assertEquals(listOf(RelationshipStyle.non_monogamy, RelationshipStyle.polyamory), card.relationshipStyles)
    }
}
