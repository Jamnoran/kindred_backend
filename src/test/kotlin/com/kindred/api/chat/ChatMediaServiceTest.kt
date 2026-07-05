package com.kindred.api.chat

import com.kindred.api.media.MediaUploadService
import com.kindred.api.media.S3Properties
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.premium.PremiumRequiredException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Presigning is a pure offline computation, so these tests use the real presigner. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatMediaServiceTest {

    private val props = S3Properties(
        endpoint = "http://localhost:9000",
        region = "auto",
        accessKey = "test-access",
        secretKey = "test-secret",
        bucket = "kindred-media",
    )

    private val presigner: S3Presigner = S3Presigner.builder()
        .endpointOverride(URI.create(props.endpoint))
        .region(Region.of(props.region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()

    private val now = Instant.parse("2026-07-03T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val chatService: ChatService = mock()
    private val chatMedia: ChatMediaRepository = mock()
    private val uploads = MediaUploadService(presigner, props, clock)
    private val service = ChatMediaService(chatService, chatMedia, uploads, presigner, props, clock)

    @AfterAll
    fun closePresigner() = presigner.close()

    private fun approvedMedia() = ChatMedia(
        id = 30L, storageKey = "chat-media/" + "ab".repeat(16), ownerUserId = 1L, conversationId = 7L,
        moderationStatus = ModerationStatus.approved, blurhash = "LKO2",
    )

    @Test
    fun `upload presign checks membership plus the premium image gate and lands in quarantine`() {
        val upload = service.presignUpload(1L, 7L, "image/jpeg")

        // PER_CLASS lifecycle shares the mock across tests, so no exact counts here
        verify(chatService, atLeastOnce()).requireImageMessaging(1L, 7L)
        assertTrue(upload.storageKey.startsWith("quarantine/"))
        assertTrue(upload.uploadUrl.startsWith("http://localhost:9000/kindred-media/quarantine/"))
    }

    @Test
    fun `upload presign is refused when neither participant is premium`() {
        whenever(chatService.requireImageMessaging(5L, 7L)).doThrow(PremiumRequiredException("sending images in this chat"))

        assertThrows<PremiumRequiredException> { service.presignUpload(5L, 7L, "image/jpeg") }
    }

    @Test
    fun `signed urls cover all sizes and expire after five minutes`() {
        whenever(chatMedia.findById(30L)).thenReturn(Optional.of(approvedMedia()))

        val response = service.signedUrls(1L, 7L, 30L)

        verify(chatService, atLeastOnce()).requireMembership(1L, 7L)
        assertEquals(30L, response.mediaId)
        assertEquals(now.plusSeconds(300), response.expiresAt)
        for (url in listOf(response.urls.thumb, response.urls.card, response.urls.full)) {
            assertTrue(url.startsWith("http://localhost:9000/kindred-media/chat-media/"))
            assertTrue("X-Amz-Expires=300" in url)
            assertTrue("X-Amz-Signature=" in url)
        }
        assertTrue("/thumb.jpg" in response.urls.thumb)
        assertTrue("/card.jpg" in response.urls.card)
        assertTrue("/full.jpg" in response.urls.full)
    }

    @Test
    fun `non-members cannot fetch urls`() {
        whenever(chatService.requireMembership(99L, 7L)).doThrow(ConversationNotFoundException())

        assertThrows<ConversationNotFoundException> { service.signedUrls(99L, 7L, 30L) }
    }

    @Test
    fun `media from another conversation is not found`() {
        whenever(chatMedia.findById(30L)).thenReturn(Optional.of(approvedMedia().apply { conversationId = 8L }))

        assertThrows<ChatMediaNotFoundException> { service.signedUrls(1L, 7L, 30L) }
    }

    @Test
    fun `rejected media is indistinguishable from missing`() {
        whenever(chatMedia.findById(30L)).thenReturn(
            Optional.of(approvedMedia().apply { moderationStatus = ModerationStatus.rejected }),
        )

        assertThrows<ChatMediaNotFoundException> { service.signedUrls(1L, 7L, 30L) }
        whenever(chatMedia.findById(31L)).thenReturn(Optional.empty())
        assertThrows<ChatMediaNotFoundException> { service.signedUrls(1L, 7L, 31L) }
    }

    @Test
    fun `pending media is a conflict, not a leak`() {
        whenever(chatMedia.findById(30L)).thenReturn(
            Optional.of(approvedMedia().apply { moderationStatus = ModerationStatus.pending }),
        )

        assertThrows<ChatMediaNotReadyException> { service.signedUrls(1L, 7L, 30L) }
    }
}
