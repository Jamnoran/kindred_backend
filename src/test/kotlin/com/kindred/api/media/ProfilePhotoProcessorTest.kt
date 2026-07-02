package com.kindred.api.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilePhotoProcessorTest {

    private val processor = ProfilePhotoProcessor()

    private fun image(width: Int, height: Int, format: String): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(200, 100, 50)
        g.fillRect(0, 0, width, height)
        g.color = Color.BLUE
        g.fillOval(width / 4, height / 4, width / 2, height / 2)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, format, out)
        return out.toByteArray()
    }

    /** Splices a minimal EXIF APP1 segment right after the JPEG SOI marker. */
    private fun withExifSegment(jpeg: ByteArray): ByteArray {
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) +
            byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0, 8, 0, 0, 0, 0, 0) // TIFF header, empty IFD
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) {
                if (haystack[i + j] != n[j]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun `magic bytes identify jpeg and png and reject other content`() {
        assertEquals("image/jpeg", MagicBytes.detectImageType(image(10, 10, "jpg")))
        assertEquals("image/png", MagicBytes.detectImageType(image(10, 10, "png")))
        assertEquals("image/webp", MagicBytes.detectImageType("RIFF____WEBPVP8 ".toByteArray(Charsets.US_ASCII)))
        assertNull(MagicBytes.detectImageType("<html>not an image</html>".toByteArray()))
        assertNull(MagicBytes.detectImageType(ByteArray(0)))
    }

    @Test
    fun `re-encodes into bounded jpeg sizes`() {
        val processed = processor.process(image(2000, 1000, "png"))

        assertEquals(setOf("thumb", "card", "full"), processed.sizes.keys)
        processed.sizes.values.forEach { bytes ->
            assertEquals("image/jpeg", MagicBytes.detectImageType(bytes))
        }
        val thumb = ImageIO.read(ByteArrayInputStream(processed.sizes.getValue("thumb")))
        assertTrue(thumb.width <= 256 && thumb.height <= 256)
        val full = ImageIO.read(ByteArrayInputStream(processed.sizes.getValue("full")))
        assertTrue(full.width <= 1600 && full.height <= 1600)
        // aspect ratio preserved
        assertEquals(2.0, full.width.toDouble() / full.height, 0.1)
    }

    @Test
    fun `small images are not upscaled`() {
        val processed = processor.process(image(100, 80, "jpg"))
        val full = ImageIO.read(ByteArrayInputStream(processed.sizes.getValue("full")))
        assertEquals(100, full.width)
        assertEquals(80, full.height)
    }

    @Test
    fun `re-encoding strips EXIF metadata`() {
        val tainted = withExifSegment(image(64, 64, "jpg"))
        assertTrue(containsAscii(tainted, "Exif"), "test input should carry an EXIF segment")

        val processed = processor.process(tainted)

        processed.sizes.values.forEach { bytes ->
            assertFalse(containsAscii(bytes, "Exif"), "output must not contain an EXIF segment")
        }
    }

    @Test
    fun `produces a well-formed blurhash`() {
        val processed = processor.process(image(300, 200, "png"))
        // 4x3 components → 1 flag + 1 max + 4 DC + 11 * 2 AC = 28 chars
        assertEquals(28, processed.blurhash.length)
        // deterministic for identical input
        assertEquals(processed.blurhash, processor.process(image(300, 200, "png")).blurhash)
    }

    @Test
    fun `rejects bytes that are not a supported image`() {
        assertThrows<UnsupportedImageBytesException> { processor.process("MZ-this-is-an-exe".toByteArray()) }
        assertThrows<UnsupportedImageBytesException> { processor.process(ByteArray(0)) }
        // valid magic bytes but truncated/corrupt body must also fail
        assertThrows<UnsupportedImageBytesException> {
            processor.process(image(64, 64, "jpg").copyOfRange(0, 40))
        }
    }
}
