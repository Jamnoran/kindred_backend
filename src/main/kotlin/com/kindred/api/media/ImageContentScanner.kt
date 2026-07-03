package com.kindred.api.media

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * What surfaces do with each verdict is *their* policy (ARCHITECTURE.md §9):
 * - [CLEAN] — serve normally everywhere.
 * - [NSFW] — adult but legal. Profile photos reject it; chat approves it flagged,
 *   and clients keep it blurred until the viewer opts in.
 * - [DISALLOWED] — never served, deleted, on any surface. CSAM always lands here
 *   (and a real provider must also handle the mandated reporting, e.g. NCMEC).
 */
enum class ScanVerdict { CLEAN, NSFW, DISALLOWED }

data class ScanResult(val verdict: ScanVerdict, val reason: String? = null)

/**
 * NSFW + CSAM scanning hook (ARCHITECTURE.md §9). Every processed image passes
 * through here before it can be served. Real providers (PhotoDNA / Thorn Safer /
 * Cloudflare CSAM tool + an NSFW classifier) MUST replace the stub before any
 * public launch — this is a legal requirement, not a nice-to-have.
 */
interface ImageContentScanner {
    fun scan(imageBytes: ByteArray): ScanResult
}

@Component
class StubImageContentScanner : ImageContentScanner {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun warn() {
        log.warn(
            "StubImageContentScanner active: images are NOT scanned for CSAM/NSFW. " +
                "Replace with real providers before launch (ARCHITECTURE.md §9).",
        )
    }

    override fun scan(imageBytes: ByteArray): ScanResult = ScanResult(ScanVerdict.CLEAN)
}
