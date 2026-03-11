package de.codevoid.aWayToGo.routing

/**
 * Result of a [RoutingRepository.calculateRoute] call.
 *
 * [Success] — a valid route was found.
 * [NoRoute] — the engine could not connect the waypoints (missing segments,
 *             no passable road in the area, etc.).
 * [Error] — an engine or I/O failure occurred; [message] carries a developer-
 *           readable description. Do not show [message] directly to users.
 */
sealed class RoutingResult {
    data class Success(val route: Route) : RoutingResult()
    data object NoRoute : RoutingResult()
    data class Error(val message: String) : RoutingResult()
}
