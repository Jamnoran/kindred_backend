package com.kindred.api.chat

import com.kindred.api.media.MediaUploadService
import com.kindred.api.media.PresignedUpload
import com.kindred.api.media.S3Properties
import com.kindred.api.photo.ModerationStatus
import com.kindred.api.photo.PhotoUrls
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Clock
import java.time.Duration

/**
 * The serving side of §6B: chat images live in a private prefix and are only ever
 * reachable through short-lived signed URLs, minted per fetch, per participant.
 * Membership is authorized on every call — there is no public URL to leak.
 */
@Service
class ChatMediaService(
    private val chatService: ChatService,
    private val chatMedia: ChatMediaRepository,
    private val mediaUploadService: MediaUploadService,
    private val presigner: S3Presigner,
    private val props: S3Properties,
    private val clock: Clock,
) {

    companion object {
        /** §6B: signed GET URLs expire after 5 minutes. */
        val SIGNED_URL_TTL: Duration = Duration.ofMinutes(5)
    }

    fun presignUpload(userId: Long, conversationId: Long, contentType: String): PresignedUpload {
        chatService.requireMembership(userId, conversationId)
        return mediaUploadService.presignChatImageUpload(userId, conversationId, contentType)
    }

    @Transactional(readOnly = true)
    fun signedUrls(userId: Long, conversationId: Long, mediaId: Long): ChatMediaUrlsResponse {
        chatService.requireMembership(userId, conversationId)
        val media = chatMedia.findById(mediaId).orElse(null)
        // wrong conversation or rejected → same not-found as never existing
        if (media == null || media.conversationId != conversationId ||
            media.moderationStatus == ModerationStatus.rejected
        ) {
            throw ChatMediaNotFoundException()
        }
        if (media.moderationStatus == ModerationStatus.pending) throw ChatMediaNotReadyException()

        return ChatMediaUrlsResponse(
            mediaId = requireNotNull(media.id),
            urls = PhotoUrls(
                thumb = presignGet("${media.storageKey}/thumb.jpg"),
                card = presignGet("${media.storageKey}/card.jpg"),
                full = presignGet("${media.storageKey}/full.jpg"),
            ),
            expiresAt = clock.instant().plus(SIGNED_URL_TTL),
        )
    }

    private fun presignGet(key: String): String = presigner.presignGetObject(
        GetObjectPresignRequest.builder()
            .signatureDuration(SIGNED_URL_TTL)
            .getObjectRequest(GetObjectRequest.builder().bucket(props.bucket).key(key).build())
            .build(),
    ).url().toString()
}
