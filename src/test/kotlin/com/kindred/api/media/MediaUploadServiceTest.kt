package com.kindred.api.media

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Presigning is a pure offline computation, so these tests use the real presigner. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaUploadServiceTest {

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

    private val now = Instant.parse("2026-07-02T12:00:00Z")
    private val service = MediaUploadService(presigner, props, Clock.fixed(now, ZoneOffset.UTC))

    @AfterAll
    fun closePresigner() = presigner.close()

    @Test
    fun `presigns a PUT into the quarantine prefix with a random key`() {
        val upload = service.presignProfilePhotoUpload(7L, "image/jpeg")

        assertTrue(upload.storageKey.startsWith("quarantine/"))
        assertEquals("quarantine/".length + 32, upload.storageKey.length) // 16 random bytes as hex
        assertTrue(upload.uploadUrl.startsWith("http://localhost:9000/kindred-media/quarantine/"))
        assertTrue("X-Amz-Expires=600" in upload.uploadUrl)
        assertTrue("X-Amz-Signature=" in upload.uploadUrl)
        assertEquals(now.plusSeconds(600), upload.expiresAt)
    }

    @Test
    fun `keys are unique per request and are plain random hex`() {
        val a = service.presignProfilePhotoUpload(7L, "image/png")
        val b = service.presignProfilePhotoUpload(7L, "image/png")

        assertTrue(a.storageKey != b.storageKey)
        assertTrue(a.storageKey.removePrefix("quarantine/").matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `content type is normalized and validated`() {
        val upload = service.presignProfilePhotoUpload(7L, " IMAGE/JPEG ")
        assertTrue(upload.storageKey.startsWith("quarantine/"))

        assertThrows<UnsupportedImageTypeException> { service.presignProfilePhotoUpload(7L, "image/gif") }
        assertThrows<UnsupportedImageTypeException> { service.presignProfilePhotoUpload(7L, "application/pdf") }
        assertThrows<UnsupportedImageTypeException> { service.presignProfilePhotoUpload(7L, "text/html") }
    }
}
