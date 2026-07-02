package com.kindred.api.photo

import com.kindred.api.media.ImageContentScanner
import com.kindred.api.media.MediaStorage
import com.kindred.api.media.ProfilePhotoProcessor
import com.kindred.api.media.ScanResult
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Optional
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Uses the real ProfilePhotoProcessor with real image bytes; only I/O is mocked. */
class PhotoProcessingServiceTest {

    private val photos: PhotoRepository = mock()
    private val storage: MediaStorage = mock()
    private val scanner: ImageContentScanner = mock()
    private val service = PhotoProcessingService(photos, storage, ProfilePhotoProcessor(), scanner)

    private val quarantineKey = "quarantine/" + "cd".repeat(16)

    private fun jpeg(): ByteArray {
        val img = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(90, 140, 60)
        g.fillRect(0, 0, 400, 300)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    private fun pendingPhoto() = Photo(id = 5L, profileUserId = 1L, storageKey = quarantineKey)

    @Test
    fun `happy path promotes to profiles prefix and approves`() {
        val photo = pendingPhoto()
        whenever(photos.findById(5L)).thenReturn(Optional.of(photo))
        whenever(storage.get(quarantineKey)).thenReturn(jpeg())
        whenever(scanner.scan(any())).thenReturn(ScanResult(allowed = true))
        whenever(photos.save(any())).thenAnswer { it.arguments[0] }

        service.process(5L)

        assertEquals(ModerationStatus.approved, photo.moderationStatus)
        assertNotNull(photo.blurhash)
        assertTrue(photo.storageKey.matches(Regex("profiles/[0-9a-f]{32}")))

        val putKeys = argumentCaptor<String>().apply {
            verify(storage, times(3)).put(capture(), any(), eq("image/jpeg"))
        }.allValues
        assertEquals(
            setOf("${photo.storageKey}/thumb.jpg", "${photo.storageKey}/card.jpg", "${photo.storageKey}/full.jpg"),
            putKeys.toSet(),
        )
        verify(storage).delete(quarantineKey)
    }

    @Test
    fun `invalid bytes reject the photo and drop the quarantine object`() {
        val photo = pendingPhoto()
        whenever(photos.findById(5L)).thenReturn(Optional.of(photo))
        whenever(storage.get(quarantineKey)).thenReturn("#!/bin/sh disguised".toByteArray())

        service.process(5L)

        assertEquals(ModerationStatus.rejected, photo.moderationStatus)
        verify(storage, never()).put(any(), any(), any())
        verify(storage).delete(quarantineKey)
    }

    @Test
    fun `failed content scan rejects the photo`() {
        val photo = pendingPhoto()
        whenever(photos.findById(5L)).thenReturn(Optional.of(photo))
        whenever(storage.get(quarantineKey)).thenReturn(jpeg())
        whenever(scanner.scan(any())).thenReturn(ScanResult(allowed = false, reason = "nsfw"))

        service.process(5L)

        assertEquals(ModerationStatus.rejected, photo.moderationStatus)
        verify(storage, never()).put(any(), any(), any())
        verify(storage).delete(quarantineKey)
    }

    @Test
    fun `already-processed photos are skipped on retry`() {
        val done = Photo(id = 5L, profileUserId = 1L, storageKey = "profiles/abc", moderationStatus = ModerationStatus.approved)
        whenever(photos.findById(5L)).thenReturn(Optional.of(done))

        service.process(5L)

        verify(storage, never()).get(any())
        verify(photos, never()).save(any())
    }

    @Test
    fun `vanished photos are a no-op`() {
        whenever(photos.findById(5L)).thenReturn(Optional.empty())

        service.process(5L)

        verify(storage, never()).get(any())
    }
}
