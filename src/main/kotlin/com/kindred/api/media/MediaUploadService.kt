package com.kindred.api.media

import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant

class UnsupportedImageTypeException(contentType: String) :
    RuntimeException("unsupported image content type: $contentType — allowed: ${MediaUploadService.ALLOWED_IMAGE_TYPES.joinToString()}")

data class PresignedUpload(
    val uploadUrl: String,
    val storageKey: String,
    val expiresAt: Instant,
)

/**
 * Pre-signed uploads into the `quarantine/` prefix (ARCHITECTURE.md §6A): bytes go
 * straight to object storage, never through the API. Nothing is served from
 * quarantine — the processing worker (validate → re-encode → EXIF strip → moderate)
 * promotes objects to `profiles/` and only then creates a photos row.
 */
@Service
class MediaUploadService(
    private val presigner: S3Presigner,
    private val props: S3Properties,
    private val clock: Clock,
) {

    companion object {
        val ALLOWED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val UPLOAD_URL_TTL: Duration = Duration.ofMinutes(10)
        const val QUARANTINE_PREFIX = "quarantine/"
    }

    private val random = SecureRandom()

    fun presignProfilePhotoUpload(userId: Long, contentType: String): PresignedUpload =
        presignQuarantineUpload(contentType)

    /** Chat images (§6B) start in the same quarantine; they promote to `chat-media/`, never `profiles/`. */
    fun presignChatImageUpload(userId: Long, conversationId: Long, contentType: String): PresignedUpload =
        presignQuarantineUpload(contentType)

    private fun presignQuarantineUpload(contentType: String): PresignedUpload {
        val normalized = contentType.trim().lowercase()
        if (normalized !in ALLOWED_IMAGE_TYPES) {
            throw UnsupportedImageTypeException(contentType)
        }
        // Random, non-enumerable key (§6); user id deliberately not part of it
        val key = QUARANTINE_PREFIX + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        // Attribution (uploader, conversation) is not embedded as S3 object metadata because
        // presigning metadata bakes x-amz-meta-* into X-Amz-SignedHeaders; the browser would
        // need to send those headers verbatim or the PUT signature fails (400). Attribution is
        // instead derived at registration time from the authenticated session.
        val objectRequest = PutObjectRequest.builder()
            .bucket(props.bucket)
            .key(key)
            .contentType(normalized)
            .build()
        val presigned = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(UPLOAD_URL_TTL)
                .putObjectRequest(objectRequest)
                .build(),
        )
        return PresignedUpload(
            uploadUrl = presigned.url().toString(),
            storageKey = key,
            expiresAt = clock.instant().plus(UPLOAD_URL_TTL),
        )
    }
}
