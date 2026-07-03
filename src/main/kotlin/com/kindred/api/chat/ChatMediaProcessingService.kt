package com.kindred.api.chat

import com.kindred.api.media.ImageContentScanner
import com.kindred.api.media.MediaStorage
import com.kindred.api.media.ProfilePhotoProcessor
import com.kindred.api.media.ScanVerdict
import com.kindred.api.media.UnsupportedImageBytesException
import com.kindred.api.photo.ModerationStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

/**
 * The JobRunr-executed side of the §6B pipeline — the same validate/re-encode/
 * EXIF-strip/scan treatment as profile photos, but promotion goes to the private
 * `chat-media/` prefix that is only reachable through signed URLs. Participants
 * learn the outcome through a `media` event on the conversation topic.
 */
@Service
class ChatMediaProcessingService(
    private val chatMedia: ChatMediaRepository,
    private val storage: MediaStorage,
    private val processor: ProfilePhotoProcessor,
    private val scanner: ImageContentScanner,
    private val relay: ObjectProvider<ChatEventRelay>,
) {

    companion object {
        const val CHAT_MEDIA_PREFIX = "chat-media/"
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    @Transactional
    fun process(mediaId: Long) {
        val media = chatMedia.findById(mediaId).orElse(null)
        if (media == null) {
            log.warn("chat media {} vanished before processing — skipping", mediaId)
            return
        }
        // idempotency: JobRunr retries must not reprocess finished media
        if (media.moderationStatus != ModerationStatus.pending ||
            !media.storageKey.startsWith("quarantine/")
        ) {
            return
        }
        val quarantineKey = media.storageKey

        val original = storage.get(quarantineKey)
        val processed = try {
            processor.process(original)
        } catch (e: UnsupportedImageBytesException) {
            log.info("rejecting chat media {}: {}", mediaId, e.message)
            reject(media, quarantineKey)
            return
        }

        // chat surface policy (§9): NSFW is allowed but flagged — clients keep it
        // blurred until the viewer opts in. Only DISALLOWED (CSAM etc.) is dropped.
        val scan = scanner.scan(processed.sizes.getValue("full"))
        if (scan.verdict == ScanVerdict.DISALLOWED) {
            log.warn("chat media {} failed content scan: {}", mediaId, scan.reason)
            reject(media, quarantineKey)
            return
        }

        val baseKey = CHAT_MEDIA_PREFIX + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        processed.sizes.forEach { (name, bytes) ->
            storage.put("$baseKey/$name.jpg", bytes, "image/jpeg")
        }
        storage.delete(quarantineKey)

        media.storageKey = baseKey
        media.blurhash = processed.blurhash
        media.isNsfw = scan.verdict == ScanVerdict.NSFW
        media.moderationStatus = ModerationStatus.approved
        chatMedia.save(media)
        broadcastOutcome(media)
    }

    private fun reject(media: ChatMedia, quarantineKey: String) {
        media.moderationStatus = ModerationStatus.rejected
        chatMedia.save(media)
        try {
            storage.delete(quarantineKey)
        } catch (e: Exception) {
            log.warn("could not delete quarantine object {}: {}", quarantineKey, e.message)
        }
        broadcastOutcome(media)
    }

    /** Lets both participants swap the placeholder for the image (or drop it) live. */
    private fun broadcastOutcome(media: ChatMedia) {
        relay.ifAvailable?.publish(
            ChatEvent(
                type = "media",
                conversationId = media.conversationId,
                media = ChatMediaSummary.from(media),
            ),
        )
    }
}
