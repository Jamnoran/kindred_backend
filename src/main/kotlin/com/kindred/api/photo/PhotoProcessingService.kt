package com.kindred.api.photo

import com.kindred.api.media.ImageContentScanner
import com.kindred.api.media.MediaStorage
import com.kindred.api.media.ProfilePhotoProcessor
import com.kindred.api.media.UnsupportedImageBytesException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom

/**
 * The JobRunr-executed side of the §6A pipeline: quarantine object → validate/
 * re-encode/resize/blurhash → CSAM+NSFW scan → promote to `profiles/` → approve.
 * Any rejection deletes the quarantine bytes.
 */
@Service
class PhotoProcessingService(
    private val photos: PhotoRepository,
    private val storage: MediaStorage,
    private val processor: ProfilePhotoProcessor,
    private val scanner: ImageContentScanner,
) {

    companion object {
        const val PROFILES_PREFIX = "profiles/"
    }

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    @Transactional
    fun process(photoId: Long) {
        val photo = photos.findById(photoId).orElse(null)
        if (photo == null) {
            log.warn("photo {} vanished before processing — skipping", photoId)
            return
        }
        // idempotency: JobRunr retries must not reprocess a finished photo
        if (photo.moderationStatus != ModerationStatus.pending || !photo.storageKey.startsWith("quarantine/")) {
            return
        }
        val quarantineKey = photo.storageKey

        val original = storage.get(quarantineKey)
        val processed = try {
            processor.process(original)
        } catch (e: UnsupportedImageBytesException) {
            log.info("rejecting photo {}: {}", photoId, e.message)
            reject(photo, quarantineKey)
            return
        }

        val scan = scanner.scan(processed.sizes.getValue("full"))
        if (!scan.allowed) {
            log.warn("photo {} failed content scan: {}", photoId, scan.reason)
            reject(photo, quarantineKey)
            return
        }

        val baseKey = PROFILES_PREFIX + ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        processed.sizes.forEach { (name, bytes) ->
            storage.put("$baseKey/$name.jpg", bytes, "image/jpeg")
        }
        storage.delete(quarantineKey)

        photo.storageKey = baseKey
        photo.blurhash = processed.blurhash
        photo.moderationStatus = ModerationStatus.approved
        photos.save(photo)
    }

    /** Best-effort removal of a photo's stored objects (quarantine or derived sizes). */
    fun deleteObjects(photo: Photo) {
        val keys = if (photo.storageKey.startsWith(PROFILES_PREFIX)) {
            ProfilePhotoProcessor.SIZE_BOUNDS.keys.map { "${photo.storageKey}/$it.jpg" }
        } else {
            listOf(photo.storageKey)
        }
        keys.forEach { key ->
            try {
                storage.delete(key)
            } catch (e: Exception) {
                log.warn("could not delete object {}: {}", key, e.message)
            }
        }
    }

    private fun reject(photo: Photo, quarantineKey: String) {
        photo.moderationStatus = ModerationStatus.rejected
        photos.save(photo)
        try {
            storage.delete(quarantineKey)
        } catch (e: Exception) {
            log.warn("could not delete quarantine object {}: {}", quarantineKey, e.message)
        }
    }
}
