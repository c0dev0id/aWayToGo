package de.codevoid.aWayToGo.routing

/**
 * Domain interface for route calculation.
 *
 * The sole implementation is [BRouterEngine], which wraps the BRouter library.
 * No other class in the app imports or references BRouter directly.
 *
 * Route calculation is CPU-heavy; all implementations must run their work on
 * [kotlinx.coroutines.Dispatchers.Default] and never block the main thread.
 *
 * ### Usage
 * ```kotlin
 * val result = routingRepository.calculateRoute(
 *     from    = RoutePoint(lat = 48.137, lng = 11.576),
 *     to      = RoutePoint(lat = 47.811, lng = 13.033),
 *     via     = emptyList(),
 *     profile = RoutingProfile.MOTORCYCLE_SCENIC,
 * )
 * when (result) {
 *     is RoutingResult.Success  -> drawRoute(result.route)
 *     is RoutingResult.NoRoute  -> showNoRouteMessage()
 *     is RoutingResult.Error    -> Log.e(TAG, result.message)
 * }
 * ```
 */
interface RoutingRepository {

    /**
     * Calculate a route from [from] to [to], optionally passing through [via] waypoints
     * in order, using the given [profile].
     *
     * Suspends on [kotlinx.coroutines.Dispatchers.Default].
     * Returns [RoutingResult.Success], [RoutingResult.NoRoute], or [RoutingResult.Error].
     */
    suspend fun calculateRoute(
        from: RoutePoint,
        to: RoutePoint,
        via: List<RoutePoint> = emptyList(),
        profile: RoutingProfile = RoutingProfile.MOTORCYCLE_FAST,
    ): RoutingResult
}
