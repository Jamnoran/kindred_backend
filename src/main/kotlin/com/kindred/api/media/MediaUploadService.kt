package com.kindred.api.media

import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
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

data class PresignedDownload(
    val url: String,
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
        /** Chat media is only ever served through these (§6B). */
        val DOWNLOAD_URL_TTL: Duration = Duration.ofMinutes(5)
        const val QUARANTINE_PREFIX = "quarantine/"
        const val CHAT_QUARANTINE_PREFIX = "chat-quarantine/"
    }

    private val random = SecureRandom()

    fun presignProfilePhotoUpload(userId: Long, contentType: String): PresignedUpload =
        presignUpload(QUARANTINE_PREFIX, userId, contentType)

    fun presignChatImageUpload(userId: Long, contentType: String): PresignedUpload =
        presignUpload(CHAT_QUARANTINE_PREFIX, userId, contentType)

    /** Short-lived signed GET — the only way private chat media is ever fetched. */
    fun presignDownload(storageKey: String): PresignedDownload {
        val presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(DOWNLOAD_URL_TTL)
                .getObjectRequest(GetObjectRequest.builder().bucket(props.bucket).key(storageKey).build())
                .build(),
        )
        return PresignedDownload(url = presigned.url().toString(), expiresAt = clock.instant().plus(DOWNLOAD_URL_TTL))
    }

    private fun presignUpload(prefix: String, userId: Long, contentType: String): PresignedUpload {
        val normalized = contentType.trim().lowercase()
        if (normalized !in ALLOWED_IMAGE_TYPES) {
            throw UnsupportedImageTypeException(contentType)
        }
        // Random, non-enumerable key (§6); user id deliberately not part of it
        val key = prefix + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val objectRequest = PutObjectRequest.builder()
            .bucket(props.bucket)
            .key(key)
            .contentType(normalized)
            // recorded so the worker can attribute the upload without trusting the client
            .metadata(mapOf("uploader-user-id" to userId.toString()))
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
