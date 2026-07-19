package com.kindred.api.geo

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the real bundled dataset (geo/cities.tsv.gz) — no fixtures. */
class CityIndexTest {

    private val index = CityIndex()

    @Test
    fun `nearest resolves known coordinates to their city`() {
        assertEquals("Malmö", index.nearest(55.6, 13.0)?.name)
        assertEquals("Stockholm", index.nearest(59.3293, 18.0686)?.name)
        assertEquals("SE", index.nearest(55.6, 13.0)?.country)
    }

    @Test
    fun `nearest works across the antimeridian`() {
        // Suva (Fiji) side of the date line; a naive lng delta of ~359° must not win
        val city = index.nearest(-18.14, 178.44)
        assertEquals("FJ", city?.country)
    }

    @Test
    fun `search is a diacritic-insensitive prefix match, biggest places first`() {
        assertEquals("Malmö", index.search("malmo", 8).first().name)
        assertEquals("Malmö", index.search("Malmö", 8).first().name)
        // ø does not NFD-decompose — needs the explicit mapping
        assertTrue(index.search("tromso", 8).any { it.name == "Tromsø" })
        // biggest match first: "lond" → London (GB) before smaller Londons
        val london = index.search("lond", 8).first()
        assertEquals("London", london.name)
        assertEquals("GB", london.country)
    }

    @Test
    fun `search respects the limit and handles misses`() {
        assertEquals(3, index.search("san", 3).size)
        assertEquals(emptyList(), index.search("zzzzzzzz", 8))
        assertEquals(emptyList(), index.search("   ", 8))
    }

    @Test
    fun `normalization strips diacritics and maps special letters`() {
        assertEquals("malmo", CityIndex.normalize("Malmö"))
        assertEquals("sao paulo", CityIndex.normalize("São Paulo"))
        assertEquals("tromso", CityIndex.normalize("Tromsø"))
        assertEquals("lodz", CityIndex.normalize("Łódź"))
    }
}
