package com.kindred.api.photo

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreatePhotoRequest(
    /** The quarantine storageKey returned by POST /api/v1/media/profile-photo-uploads */
    @field:NotBlank @field:Size(max = 255)
    val storageKey: String,
)

data class PhotoUrls(val thumb: String, val card: String, val full: String)

data class PhotoResponse(
    val id: Long,
    val status: ModerationStatus,
    val isPrimary: Boolean,
    val sortOrder: Int,
    val blurhash: String?,
    /** null until the photo is approved */
    val urls: PhotoUrls?,
) {
    companion object {
        fun from(photo: Photo, publicBaseUrl: String): PhotoResponse {
            val base = publicBaseUrl.trimEnd('/')
            val urls = if (photo.moderationStatus == ModerationStatus.approved) {
                PhotoUrls(
                    thumb = "$base/${photo.storageKey}/thumb.jpg",
                    card = "$base/${photo.storageKey}/card.jpg",
                    full = "$base/${photo.storageKey}/full.jpg",
                )
            } else {
                null
            }
            return PhotoResponse(
                id = requireNotNull(photo.id),
                status = photo.moderationStatus,
                isPrimary = photo.isPrimary,
                sortOrder = photo.sortOrder,
                blurhash = photo.blurhash,
                urls = urls,
            )
        }
    }
}
