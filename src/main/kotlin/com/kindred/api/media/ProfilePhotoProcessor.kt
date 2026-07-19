package com.kindred.api.media

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.nio.JpegWriter
import org.springframework.stereotype.Component

class UnsupportedImageBytesException(message: String) : RuntimeException(message)

data class ProcessedPhoto(
    /** size name (thumb/card/full) → re-encoded JPEG bytes */
    val sizes: Map<String, ByteArray>,
    val blurhash: String,
)

/**
 * The §6A pipeline core: validate by magic bytes, re-encode (which neutralizes most
 * malicious payloads and drops all metadata, EXIF/GPS included), generate responsive
 * sizes and a blurhash placeholder. Pure bytes-in/bytes-out — no I/O — so it is fully
 * unit-testable.
 */
@Component
class ProfilePhotoProcessor {

    companion object {
        val SIZE_BOUNDS = mapOf("thumb" to 256, "card" to 800, "full" to 1600)
        private const val JPEG_QUALITY = 85
    }

    fun process(original: ByteArray): ProcessedPhoto {
        MagicBytes.detectImageType(original)
            ?: throw UnsupportedImageBytesException("bytes are not a supported image format (jpeg/png/webp)")
        val image = try {
            ImmutableImage.loader().fromBytes(original)
        } catch (e: Exception) {
            throw UnsupportedImageBytesException("image could not be decoded: ${e.message}")
        }
        val writer = JpegWriter(JPEG_QUALITY, false)
        val sizes = SIZE_BOUNDS.mapValues { (_, bound) ->
            image.bound(bound, bound).bytes(writer)
        }
        val blurhash = BlurHash.encode(image.scaleTo(32, 32))
        return ProcessedPhoto(sizes, blurhash)
    }
}
