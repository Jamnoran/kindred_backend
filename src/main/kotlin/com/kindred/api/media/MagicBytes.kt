package com.kindred.api.media

/**
 * Format detection by magic bytes, never by extension or client-declared content
 * type (ARCHITECTURE.md §6A — rejects polyglots/disguised files before decoding).
 */
object MagicBytes {

    fun detectImageType(bytes: ByteArray): String? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"

        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() -> "image/png"

        bytes.size >= 12 &&
            bytes.decodeAscii(0, 4) == "RIFF" && bytes.decodeAscii(8, 4) == "WEBP" -> "image/webp"

        else -> null
    }

    private fun ByteArray.decodeAscii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)
}
