package com.kindred.api.chat

import com.kindred.api.media.ImageContentScanner
import com.kindred.api.media.MediaStorage
import com.kindred.api.media.ProfilePhotoProcessor
import com.kindred.api.media.UnsupportedImageBytesException
import com.kindred.api.photo.ModerationStatus
import org.jobrunr.jobs.lambdas.JobRequest
import org.jobrunr.jobs.lambdas.JobRequestHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

data class ProcessChatMediaRequest(val mediaId: Long = 0) : JobRequest {
    override fun getJobRequestHandler(): Class<ProcessChatMediaRequestHandler> =
        ProcessChatMediaRequestHandler::class.java
}

@Component
class ProcessChatMediaRequestHandler(
    private val service: ChatMediaProcessingService,
) : JobRequestHandler<ProcessChatMediaRequest> {
    override fun run(jobRequest: ProcessChatMediaRequest) = service.process(jobRequest.mediaId)
}

/**
 * §6B worker: same validate → re-encode (EXIF strip) → scan pipeline as profile
 * photos, but a single bounded size, promoted into the private `chat-media/` prefix.
 * Rejections always delete the quarantine bytes.
 */
@Service
class ChatMediaProcessingService(
    private val media: MediaRepository,
    private val storage: MediaStorage,
    private val processor: ProfilePhotoProcessor,
    private val scanner: ImageContentScanner,
) {

    companion object {
        const val CHAT_MEDIA_PREFIX = "chat-media/"
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    @Transactional
    fun process(mediaId: Long) {
        val item = media.findById(mediaId).orElse(null) ?: return
        if (item.moderationStatus != ModerationStatus.pending || !item.storageKey.startsWith("chat-quarantine/")) {
            return // idempotency on JobRunr retries
        }
        val quarantineKey = item.storageKey

        val bytes = try {
            processor.process(storage.get(quarantineKey))
        } catch (e: UnsupportedImageBytesException) {
            log.info("rejecting chat media {}: {}", mediaId, e.message)
            reject(item, quarantineKey)
            return
        }

        val scan = scanner.scan(bytes.sizes.getValue("full"))
        if (!scan.allowed) {
            log.warn("chat media {} failed content scan: {}", mediaId, scan.reason)
            reject(item, quarantineKey)
            return
        }

        val finalKey = CHAT_MEDIA_PREFIX + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        storage.put(finalKey, bytes.sizes.getValue("full"), "image/jpeg")
        storage.delete(quarantineKey)

        item.storageKey = finalKey
        item.moderationStatus = ModerationStatus.approved
        media.save(item)
    }

    private fun reject(item: Media, quarantineKey: String) {
        item.moderationStatus = ModerationStatus.rejected
        media.save(item)
        try {
            storage.delete(quarantineKey)
        } catch (e: Exception) {
            log.warn("could not delete chat quarantine object {}: {}", quarantineKey, e.message)
        }
    }
}
