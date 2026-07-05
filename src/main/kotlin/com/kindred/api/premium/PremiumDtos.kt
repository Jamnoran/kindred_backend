package com.kindred.api.premium

import java.time.Instant

/** 402 — the caller must complete the one-time premium purchase first. */
class PremiumRequiredException(feature: String) :
    RuntimeException("$feature requires a premium account — a one-time upgrade")

data class PremiumStatusResponse(
    val premium: Boolean,
    val premiumSince: Instant?,
)
