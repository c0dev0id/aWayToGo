package de.codevoid.aWayToGo.routing

import android.content.Context

/**
 * BRouter-backed implementation of [RoutingRepository].
 *
 * BRouter runs as an in-process bounded service (no separate APK required).
 * This class is the only place in the app that imports or references BRouter
 * classes directly — all other code uses [RoutingRepository] and the domain
 * types ([Route], [RoutePoint], [RoutingProfile], [RoutingResult]).
 *
 * ### Profile → script mapping
 * BRouter uses Beanshell profile scripts to define routing cost functions.
 * Scripts are bundled in `assets/brouter/profiles/` and copied to internal
 * storage on first run.
 *
 * | [RoutingProfile]        | BRouter script         |
 * |-------------------------|------------------------|
 * | MOTORCYCLE_FAST         | car-fast.brf           |
 * | MOTORCYCLE_SCENIC       | trekking.brf           |
 * | MOTORCYCLE_OFFROAD      | gravel.brf             |
 *
 * Profile scripts and segment data (.rd5 files) are the two deployment
 * concerns to resolve before this stub is made functional.
 *
 * ### Segment data
 * BRouter requires pre-downloaded `.rd5` segment files for the regions to be
 * routed through. The files are stored in `getExternalFilesDir("brouter/segments4")`.
 * Segment download UI is a future feature (SettingsDomain / OfflineDomain).
 *
 * ### TODO — make this functional
 * 1. Add `brouter-core` as a Gradle dependency (or bundle the JAR).
 * 2. Copy profile scripts from assets to internal storage on first run.
 * 3. Implement [calculateRoute] using `BRouter.doRouting()` (or equivalent API).
 * 4. Parse the returned GPX/GeoJSON track into [Route] domain objects.
 */
class BRouterEngine(private val context: Context) : RoutingRepository {

    companion object {
        private val PROFILE_SCRIPTS = mapOf(
            RoutingProfile.MOTORCYCLE_FAST    to "car-fast.brf",
            RoutingProfile.MOTORCYCLE_SCENIC  to "trekking.brf",
            RoutingProfile.MOTORCYCLE_OFFROAD to "gravel.brf",
        )
    }

    override suspend fun calculateRoute(
        from: RoutePoint,
        to: RoutePoint,
        via: List<RoutePoint>,
        profile: RoutingProfile,
    ): RoutingResult {
        // TODO: implement BRouter integration.
        // See class KDoc for the step-by-step plan.
        return RoutingResult.Error("BRouter integration not yet implemented")
    }
}
