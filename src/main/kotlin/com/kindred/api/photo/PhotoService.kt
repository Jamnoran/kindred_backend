package com.kindred.api.photo

import com.kindred.api.chat.ChatMediaRepository
import com.kindred.api.profile.ProfileNotFoundException
import com.kindred.api.profile.ProfileRepository
import org.jobrunr.scheduling.JobRequestScheduler
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PhotoService(
    private val photos: PhotoRepository,
    private val profiles: ProfileRepository,
    private val chatMedia: ChatMediaRepository,
    private val jobs: JobRequestScheduler,
    private val photoProcessingService: PhotoProcessingService,
) {

    companion object {
        const val MAX_PHOTOS = 6
        private val QUARANTINE_KEY = Regex("quarantine/[0-9a-f]{32}")
    }

    /**
     * Called after the client finishes its presigned PUT: records the photo (pending)
     * and enqueues the §6A processing job.
     */
    @Transactional
    fun submit(userId: Long, storageKey: String): Photo {
        if (!profiles.existsById(userId)) {
            throw ProfileNotFoundException()
        }
        if (!QUARANTINE_KEY.matches(storageKey)) {
            throw InvalidStorageKeyException("storageKey must be one returned by the upload endpoint")
        }
        if (photos.existsByStorageKey(storageKey) || chatMedia.existsByStorageKey(storageKey)) {
            throw InvalidStorageKeyException("storageKey was already submitted")
        }
        val count = photos.countByProfileUserId(userId)
        if (count >= MAX_PHOTOS) {
            throw PhotoLimitReachedException(MAX_PHOTOS)
        }
        val photo = photos.save(
            Photo(
                profileUserId = userId,
                storageKey = storageKey,
                sortOrder = count.toInt(),
                isPrimary = count == 0L,
            ),
        )
        jobs.enqueue(ProcessProfilePhotoRequest(requireNotNull(photo.id)))
        return photo
    }

    @Transactional(readOnly = true)
    fun listOwn(userId: Long): List<Photo> = photos.findAllByProfileUserIdOrderBySortOrderAsc(userId)

    @Transactional
    fun delete(userId: Long, photoId: Long) {
        val photo = photos.findByIdAndProfileUserId(photoId, userId) ?: throw PhotoNotFoundException()
        photoProcessingService.deleteObjects(photo)
        photos.delete(photo)
        // keep the remaining photos contiguous and always have a primary
        val remaining = photos.findAllByProfileUserIdOrderBySortOrderAsc(userId)
        remaining.forEachIndexed { index, p ->
            p.sortOrder = index
            p.isPrimary = index == 0
        }
        photos.saveAll(remaining)
    }
}
