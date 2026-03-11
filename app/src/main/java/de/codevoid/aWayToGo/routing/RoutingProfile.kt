package de.codevoid.aWayToGo.routing

/**
 * Routing cost profiles exposed to the user.
 *
 * Each value maps to a BRouter profile script bundled in assets/brouter/profiles/.
 * The mapping from enum value to filename lives in [BRouterEngine] so the rest of
 * the app stays decoupled from BRouter internals.
 *
 * Start with the three profiles that cover the realistic motorcycle use cases.
 * More can be added without changing the repository interface.
 */
enum class RoutingProfile {
    /** Fastest road route — prioritises motorways and major roads. */
    MOTORCYCLE_FAST,

    /** Scenic route — prefers twisty secondary roads, avoids motorways. */
    MOTORCYCLE_SCENIC,

    /** Unpaved / adventure — allows gravel and forest roads. */
    MOTORCYCLE_OFFROAD,
}
