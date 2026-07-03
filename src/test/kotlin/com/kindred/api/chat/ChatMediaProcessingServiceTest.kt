package com.kindred.api.chat

import com.kindred.api.media.ImageContentScanner
import com.kindred.api.media.MediaStorage
import com.kindred.api.media.ProfilePhotoProcessor
import com.kindred.api.media.ScanResult
import com.kindred.api.photo.ModerationStatus
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Optional
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Uses the real ProfilePhotoProcessor with real image bytes; only I/O is mocked. */
class ChatMediaProcessingServiceTest {

    private val chatMedia: ChatMediaRepository = mock()
    private val storage: MediaStorage = mock()
    private val scanner: ImageContentScanner = mock()
    private val relayInstance: ChatEventRelay = mock()
    private val relay: ObjectProvider<ChatEventRelay> = mock {
        on { ifAvailable } doReturn relayInstance
    }
    private val service = ChatMediaProcessingService(chatMedia, storage, ProfilePhotoProcessor(), scanner, relay)

    private val quarantineKey = "quarantine/" + "cd".repeat(16)

    private fun jpeg(): ByteArray {
        val img = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(160, 80, 120)
        g.fillRect(0, 0, 400, 300)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    private fun pendingMedia() = ChatMedia(id = 30L, storageKey = quarantineKey, ownerUserId = 1L, conversationId = 7L)

    @Test
    fun `happy path promotes to the private chat-media prefix, approves, and broadcasts`() {
        val media = pendingMedia()
        whenever(chatMedia.findById(30L)).thenReturn(Optional.of(media))
        whenever(storage.get(quarantineKey)).thenReturn(jpeg())
        whenever(scanner.scan(any())).thenReturn(ScanResult(allowed = true))
        whenever(chatMedia.save(any())).thenAnswer { it.arguments[0] }

        service.process(30L)

        assertEquals(ModerationStatus.approved, media.moderationStatus)
        assertNotNull(media.blurhash)
        assertTrue(media.storageKey.matches(Regex("chat-media/[0-9a-f]{32}")))

        val putKeys = argumentCaptor<String>().apply {
            verify(storage, times(3)).put(capture(), any(), eq("image/jpeg"))
        }.allValues
        assertEquals(
            setOf("${media.storageKey}/thumb.jpg", "${media.storageKey}/card.jpg", "${media.storageKey}/full.jpg"),
            putKeys.toSet(),
        )
        verify(storage).delete(quarantineKey)
        verify(relayInstance).publish(
            ChatEvent(type = "media", conversationId = 7L, media = ChatMediaSummary(30L, ModerationStatus.approved, media.blurhash)),
        )
    }

    @Test
    fun `invalid bytes reject the media, drop quarantine, and broadcast the rejection`() {
        val media = pendingMedia()
        whenever(chatMedia.findById(30L)).thenReturn(Optional.of(media))
        whenever(storage.get(quarantineKey)).thenReturn("#!/bin/sh disguised".toByteArray())

        service.process(30L)

        assertEquals(ModerationStatus.rejected, media.moderationStatus)
        verify(storage, never()).put(any(), any(), any())
        verify(storage).delete(quarantineKey)
        verify(relayInstance).publish(
            ChatEvent(type = "media", conversationId = 7L, media = ChatMediaSummary(30L, ModerationStatus.rejected, null)),
        )
    }

    @Test
    fun `failed content scan rejects the media`() {
        val media = pendingMedia()
        whenever(chatMedia.findById(30L)).thenReturn(Optional.of(media))
        whenever(storage.get(quarantineKey)).thenReturn(jpeg())
        whenever(scanner.scan(any())).thenReturn(ScanResult(allowed = false, reason = "csam-hit"))

        service.process(30L)

        assertEquals(ModerationStatus.rejected, media.moderationStatus)
        verify(storage, never()).put(any(), any(), any())
        verify(storage).delete(quarantineKey)
    }

    @Test
    fun `already-processed media is skipped on retry`() {
        val done = ChatMedia(
            id = 30L, storageKey = "chat-media/abc", ownerUserId = 1L, conversationId = 7L,
            moderationStatus = ModerationStatus.approved,
        )
        whenever(chatMedia.findById(30L)).thenReturn(Optional.of(done))

        service.process(30L)

        verify(storage, never()).get(any())
        verify(chatMedia, never()).save(any())
    }

    @Test
    fun `vanished media is a no-op`() {
        whenever(chatMedia.findById(30L)).thenReturn(Optional.empty())

        service.process(30L)

        verify(storage, never()).get(any())
    }
}
