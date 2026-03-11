package de.codevoid.aWayToGo.routing

/**
 * A computed route returned by [RoutingRepository].
 *
 * [points] is the full geometry in routing order — the first point is near the
 * [from] waypoint and the last is near the [to] waypoint passed to the routing call.
 * BRouter snaps waypoints to the nearest routable road segment, so the start/end
 * points of the geometry may differ slightly from the requested coordinates.
 *
 * [distanceM] and [ascentM] are totals for the entire route; per-segment values
 * are available via the BRouter GPX track extension if needed later.
 *
 * [profile] records which cost profile was used, so the UI can show it in the
 * route summary without a separate lookup.
 */
data class Route(
    val points: List<RoutePoint>,
    val distanceM: Double,
    val ascentM: Double,
    val profile: RoutingProfile,
)
