package com.kindred.api.chat

import com.kindred.api.discovery.Match
import com.kindred.api.discovery.MatchRepository
import com.kindred.api.media.MediaUploadService
import com.kindred.api.media.S3Properties
import com.kindred.api.photo.InvalidStorageKeyException
import com.kindred.api.photo.ModerationStatus
import org.jobrunr.scheduling.JobRequestScheduler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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

/** Presigning is offline, so the signed-URL half runs against the real presigner. */
class ChatMediaServiceTest {

    private val media: MediaRepository = mock()
    private val conversations: ConversationRepository = mock()
    private val matches: MatchRepository = mock()
    private val jobs: JobRequestScheduler = mock()
    private val now = Instant.parse("2026-07-02T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val props = S3Properties(
        endpoint = "http://localhost:9000",
        region = "auto",
        accessKey = "test-access",
        secretKey = "test-secret",
        bucket = "kindred-media",
    )
    private val uploads = MediaUploadService(
        S3Presigner.builder()
            .endpointOverride(URI.create(props.endpoint))
            .region(Region.of(props.region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build(),
        props,
        clock,
    )

    private val service = ChatMediaService(media, ConversationAccess(conversations, matches), uploads, jobs, clock)

    private val validKey = "chat-quarantine/" + "cd".repeat(16)

    private fun stubMembership(userId: Long = 1L) {
        whenever(conversations.findById(7L)).thenReturn(Optional.of(Conversation(id = 7L, matchId = 3L)))
        whenever(matches.findById(3L)).thenReturn(Optional.of(Match(id = 3L, userA = 1L, userB = 2L)))
    }

    @Test
    fun `upload presign targets the chat quarantine prefix and needs membership`() {
        stubMembership()

        val upload = service.requestUpload(1L, 7L, "image/jpeg")
        assertTrue(upload.storageKey.startsWith("chat-quarantine/"))
        assertTrue("/kindred-media/chat-quarantine/" in upload.uploadUrl)

        assertThrows<ConversationNotFoundException> { service.requestUpload(99L, 7L, "image/jpeg") }
    }

    @Test
    fun `attach records pending media and enqueues processing`() {
        whenever(media.existsByStorageKey(validKey)).thenReturn(false)
        whenever(media.save(any())).thenAnswer { (it.arguments[0] as Media).apply { id = 30L } }

        val attached = service.attach(1L, 7L, validKey)

        assertEquals(ModerationStatus.pending, attached.moderationStatus)
        verify(jobs).enqueue(ProcessChatMediaRequest(30L))
    }

    @Test
    fun `attach rejects foreign or duplicate keys`() {
        assertThrows<InvalidStorageKeyException> { service.attach(1L, 7L, "quarantine/" + "ab".repeat(16)) }
        assertThrows<InvalidStorageKeyException> { service.attach(1L, 7L, "chat-media/../../secret") }

        whenever(media.existsByStorageKey(validKey)).thenReturn(true)
        assertThrows<InvalidStorageKeyException> { service.attach(1L, 7L, validKey) }
        verify(jobs, never()).enqueue(any<ProcessChatMediaRequest>())
    }

    @Test
    fun `signed url is 5 minutes, participants only, approved only`() {
        stubMembership()
        val approved = Media(
            id = 30L, storageKey = "chat-media/" + "ef".repeat(16),
            ownerUserId = 2L, conversationId = 7L, moderationStatus = ModerationStatus.approved,
        )
        whenever(media.findById(30L)).thenReturn(Optional.of(approved))

        val download = service.signedUrl(1L, 30L)
        assertTrue("X-Amz-Expires=300" in download.url)
        assertTrue("/kindred-media/chat-media/" in download.url)
        assertEquals(now.plusSeconds(300), download.expiresAt)

        // outsider → same 404 as nonexistence
        assertThrows<ConversationNotFoundException> { service.signedUrl(99L, 30L) }
    }

    @Test
    fun `pending or rejected media is never served`() {
        stubMembership()
        val pending = Media(id = 31L, storageKey = validKey, ownerUserId = 1L, conversationId = 7L)
        whenever(media.findById(31L)).thenReturn(Optional.of(pending))

        assertThrows<MediaNotReadyException> { service.signedUrl(1L, 31L) }

        whenever(media.findById(404L)).thenReturn(Optional.empty())
        assertThrows<MediaNotFoundException> { service.signedUrl(1L, 404L) }
    }
}
