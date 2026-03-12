package de.codevoid.aWayToGo.search

data class BoundingBox(
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
)

data class SearchResult(
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val road: String? = null,
    val houseNumber: String? = null,
    val postcode: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
)
