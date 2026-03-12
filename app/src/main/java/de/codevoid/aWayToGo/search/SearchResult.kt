package de.codevoid.aWayToGo.search

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
    val distanceMeters: Float? = null,
    val bearingDeg: Float? = null,
)
