package com.kindred.api.geo

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class CityResponse(
    val id: Long,
    val name: String,
    /** ISO 3166-1 alpha-2 country code. */
    val country: String,
    // City-centroid coordinates from the public GeoNames dataset — not user data.
    val lat: Double,
    val lng: Double,
) {
    companion object {
        fun from(city: City) = CityResponse(city.id, city.name, city.country, city.lat, city.lng)
    }
}

@Validated
@RestController
@RequestMapping("/api/v1")
class GeoController(private val cityIndex: CityIndex) {

    /** Diacritic-insensitive prefix search over ~135k GeoNames places, biggest first. */
    @GetMapping("/geo/cities")
    fun cities(
        @RequestParam @Size(min = 1, max = 100) q: String,
        @RequestParam(defaultValue = "8") @Min(1) @Max(20) limit: Int,
    ): List<CityResponse> = cityIndex.search(q, limit).map(CityResponse::from)
}
