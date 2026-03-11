package de.codevoid.aWayToGo.routing

/**
 * A single geographic point on a route or waypoint list.
 *
 * Altitude is optional — BRouter returns it when elevation data is available,
 * but callers may supply waypoints without it.
 */
data class RoutePoint(
    val lat: Double,
    val lng: Double,
    val altM: Double = 0.0,
)
