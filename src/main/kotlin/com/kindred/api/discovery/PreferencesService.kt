package com.kindred.api.discovery

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@Service
class PreferencesService(private val preferences: PreferencesRepository) {

    @Transactional(readOnly = true)
    fun get(userId: Long): Preferences =
        preferences.findById(userId).orElseGet { Preferences(userId = userId) }

    @Transactional
    fun update(userId: Long, req: UpdatePreferencesRequest): Preferences {
        if (req.ageMin > req.ageMax) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ageMin must be <= ageMax")
        }
        req.weights?.keys?.firstOrNull { it !in DiscoveryScoring.Weights.KEYS }?.let { key ->
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "unknown weight key: $key — allowed: ${DiscoveryScoring.Weights.KEYS}",
            )
        }
        val prefs = preferences.findById(userId).orElseGet { Preferences(userId = userId) }
        prefs.distanceKm = req.distanceKm
        prefs.ageMin = req.ageMin
        prefs.ageMax = req.ageMax
        prefs.genders = req.genders?.distinct()
        prefs.lookingFor = req.lookingFor?.map { it.trim().lowercase() }?.distinct()
        // Filters are stored verbatim — no umbrella expansion ("polyamory" must
        // keep meaning specifically poly, not any ENM).
        prefs.relationshipStyles = req.relationshipStyles?.distinct()
        prefs.dealbreakers = req.dealbreakers?.map { it.trim().lowercase() }?.distinct()
        prefs.weights = req.weights?.mapValues { it.value.coerceIn(0.0, 5.0) }
        return preferences.save(prefs)
    }
}
