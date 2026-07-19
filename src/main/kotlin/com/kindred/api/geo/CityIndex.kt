package com.kindred.api.geo

import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

data class City(
    val id: Long,
    val name: String,
    /** ISO 3166-1 alpha-2 country code, e.g. "SE". */
    val country: String,
    val lat: Double,
    val lng: Double,
    val population: Long,
)

/**
 * In-memory index over the bundled GeoNames extract (geo/cities.tsv.gz — all places
 * with population ≥ 1000, ~135k rows, population-descending; see CLAUDE.md for how to
 * regenerate it). Backs coarse reverse geocoding for `locationLabel` and the
 * `GET /geo/cities` autocomplete. Loaded lazily so tests and the `openapi` profile
 * don't pay the ~1s parse unless geo features are actually used.
 */
@Component
class CityIndex {

    private class Data(val cities: List<City>, val normalizedNames: Array<String>)

    private val data: Data by lazy { load() }

    /**
     * Nearest city/town to the given point — city-level granularity only, never
     * anything finer, so the result is safe to display publicly. Linear scan with an
     * equirectangular metric (~ms); location writes are rare enough not to need an
     * spatial index here.
     */
    fun nearest(lat: Double, lng: Double): City? {
        var best: City? = null
        var bestScore = Double.MAX_VALUE
        // cos of the query latitude is a good-enough longitude scale for ranking
        val lngScale = max(cos(Math.toRadians(lat)), 0.01)
        for (city in data.cities) {
            val dLat = city.lat - lat
            var dLng = abs(city.lng - lng)
            if (dLng > 180) dLng = 360 - dLng
            dLng *= lngScale
            val score = dLat * dLat + dLng * dLng
            if (score < bestScore) {
                bestScore = score
                best = city
            }
        }
        return best
    }

    /**
     * Diacritic-insensitive prefix search, biggest places first (the list is stored
     * population-descending, so the first N matches are the top N).
     */
    fun search(query: String, limit: Int): List<City> {
        val q = normalize(query.trim())
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<City>(limit)
        val d = data
        for (i in d.cities.indices) {
            if (d.normalizedNames[i].startsWith(q)) {
                out += d.cities[i]
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun load(): Data {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("geo/cities.tsv.gz")) {
            "geo/cities.tsv.gz missing from classpath"
        }
        val cities = ArrayList<City>(140_000)
        BufferedReader(InputStreamReader(GZIPInputStream(resource), Charsets.UTF_8)).use { reader ->
            reader.forEachLine { line ->
                val f = line.split('\t')
                cities += City(
                    id = f[0].toLong(),
                    name = f[1],
                    country = f[2],
                    lat = f[3].toDouble(),
                    lng = f[4].toDouble(),
                    population = f[5].toLong(),
                )
            }
        }
        return Data(cities, Array(cities.size) { normalize(cities[it].name) })
    }

    companion object {
        // Letters NFD doesn't decompose to base + combining mark
        private val NON_DECOMPOSING = mapOf(
            'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss",
            'đ' to "d", 'ð' to "d", 'þ' to "th", 'ł' to "l", 'ı' to "i",
        )

        fun normalize(s: String): String {
            val decomposed = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            val sb = StringBuilder(decomposed.length)
            for (c in decomposed) {
                when {
                    Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}
                    c in NON_DECOMPOSING -> sb.append(NON_DECOMPOSING[c])
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
