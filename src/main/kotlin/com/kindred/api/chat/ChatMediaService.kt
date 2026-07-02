package com.kindred.api.chat

import com.kindred.api.media.MediaUploadService
import com.kindred.api.media.PresignedDownload
import com.kindred.api.media.PresignedUpload
import com.kindred.api.photo.InvalidStorageKeyException
import com.kindred.api.photo.ModerationStatus
import org.jobrunr.scheduling.JobRequestScheduler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

class MediaNotFoundException : RuntimeException("media not found")

class MediaNotReadyException(status: ModerationStatus) :
    RuntimeException("media is not available (status: $status)")

@Service
class ChatMediaService(
    private val media: MediaRepository,
    private val access: ConversationAccess,
    private val uploads: MediaUploadService,
    private val jobs: JobRequestScheduler,
    private val clock: Clock,
) {

    companion object {
        private val CHAT_QUARANTINE_KEY = Regex("chat-quarantine/[0-9a-f]{32}")
    }

    fun requestUpload(userId: Long, conversationId: Long, contentType: String): PresignedUpload {
        access.requireMembership(userId, conversationId)
        return uploads.presignChatImageUpload(userId, contentType)
    }

    /** Records the uploaded quarantine object and enqueues §6B processing. */
    @Transactional
    fun attach(userId: Long, conversationId: Long, storageKey: String): Media {
        if (!CHAT_QUARANTINE_KEY.matches(storageKey)) {
            throw InvalidStorageKeyException("mediaKey must be one returned by the media-uploads endpoint")
        }
        if (media.existsByStorageKey(storageKey)) {
            throw InvalidStorageKeyException("mediaKey was already submitted")
        }
        val saved = media.save(
            Media(
                storageKey = storageKey,
                ownerUserId = userId,
                conversationId = conversationId,
                createdAt = clock.instant(),
            ),
        )
        jobs.enqueue(ProcessChatMediaRequest(requireNotNull(saved.id)))
        return saved
    }

    /**
     * The only read path for chat images: participants only, approved only,
     * 5-minute signed URL. Authorized on every fetch (§6B).
     */
    @Transactional(readOnly = true)
    fun signedUrl(userId: Long, mediaId: Long): PresignedDownload {
        val item = media.findById(mediaId).orElseThrow { MediaNotFoundException() }
        access.requireMembership(userId, item.conversationId)
        if (item.moderationStatus != ModerationStatus.approved) {
            throw MediaNotReadyException(item.moderationStatus)
        }
        return uploads.presignDownload(item.storageKey)
    }
}
