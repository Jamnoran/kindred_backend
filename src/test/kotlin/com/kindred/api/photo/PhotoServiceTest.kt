package com.kindred.api.photo

import com.kindred.api.chat.ChatMediaRepository
import com.kindred.api.profile.ProfileNotFoundException
import com.kindred.api.profile.ProfileRepository
import org.jobrunr.scheduling.JobRequestScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoServiceTest {

    private val photos: PhotoRepository = mock()
    private val profiles: ProfileRepository = mock()
    private val chatMedia: ChatMediaRepository = mock()
    private val jobs: JobRequestScheduler = mock()
    private val processing: PhotoProcessingService = mock()
    private val service = PhotoService(photos, profiles, chatMedia, jobs, processing)

    private val validKey = "quarantine/" + "ab".repeat(16)

    @Test
    fun `submit records a pending photo and enqueues processing`() {
        whenever(profiles.existsById(1L)).thenReturn(true)
        whenever(photos.existsByStorageKey(validKey)).thenReturn(false)
        whenever(photos.countByProfileUserId(1L)).thenReturn(0L)
        whenever(photos.save(any())).thenAnswer { (it.arguments[0] as Photo).apply { id = 42L } }

        val photo = service.submit(1L, validKey)

        assertEquals(ModerationStatus.pending, photo.moderationStatus)
        assertTrue(photo.isPrimary)
        assertEquals(0, photo.sortOrder)
        verify(jobs).enqueue(ProcessProfilePhotoRequest(42L))
    }

    @Test
    fun `later photos are not primary and keep sort order`() {
        whenever(profiles.existsById(1L)).thenReturn(true)
        whenever(photos.existsByStorageKey(validKey)).thenReturn(false)
        whenever(photos.countByProfileUserId(1L)).thenReturn(3L)
        whenever(photos.save(any())).thenAnswer { (it.arguments[0] as Photo).apply { id = 43L } }

        val photo = service.submit(1L, validKey)

        assertFalse(photo.isPrimary)
        assertEquals(3, photo.sortOrder)
    }

    @Test
    fun `submit requires an existing profile`() {
        whenever(profiles.existsById(1L)).thenReturn(false)

        assertThrows<ProfileNotFoundException> { service.submit(1L, validKey) }
        verify(photos, never()).save(any())
    }

    @Test
    fun `submit rejects keys outside the quarantine prefix`() {
        whenever(profiles.existsById(1L)).thenReturn(true)

        assertThrows<InvalidStorageKeyException> { service.submit(1L, "profiles/abcd") }
        assertThrows<InvalidStorageKeyException> { service.submit(1L, "quarantine/../../etc/passwd") }
        assertThrows<InvalidStorageKeyException> { service.submit(1L, "quarantine/UPPERCASE-not-hex") }
        verify(photos, never()).save(any())
    }

    @Test
    fun `submit rejects a storage key that was already submitted`() {
        whenever(profiles.existsById(1L)).thenReturn(true)
        whenever(photos.existsByStorageKey(validKey)).thenReturn(true)

        assertThrows<InvalidStorageKeyException> { service.submit(1L, validKey) }
    }

    @Test
    fun `submit enforces the photo limit`() {
        whenever(profiles.existsById(1L)).thenReturn(true)
        whenever(photos.existsByStorageKey(validKey)).thenReturn(false)
        whenever(photos.countByProfileUserId(1L)).thenReturn(PhotoService.MAX_PHOTOS.toLong())

        assertThrows<PhotoLimitReachedException> { service.submit(1L, validKey) }
    }

    @Test
    fun `delete removes objects and reassigns primary and sort order`() {
        val victim = Photo(id = 10L, profileUserId = 1L, storageKey = "profiles/aaa", isPrimary = true, sortOrder = 0)
        val second = Photo(id = 11L, profileUserId = 1L, storageKey = "profiles/bbb", isPrimary = false, sortOrder = 1)
        whenever(photos.findByIdAndProfileUserId(10L, 1L)).thenReturn(victim)
        whenever(photos.findAllByProfileUserIdOrderBySortOrderAsc(1L)).thenReturn(listOf(second))

        service.delete(1L, 10L)

        verify(processing).deleteObjects(victim)
        verify(photos).delete(victim)
        assertTrue(second.isPrimary)
        assertEquals(0, second.sortOrder)
    }

    @Test
    fun `delete of another user's photo is not found`() {
        whenever(photos.findByIdAndProfileUserId(10L, 1L)).thenReturn(null)

        assertThrows<PhotoNotFoundException> { service.delete(1L, 10L) }
        verify(photos, never()).delete(any<Photo>())
    }
}
