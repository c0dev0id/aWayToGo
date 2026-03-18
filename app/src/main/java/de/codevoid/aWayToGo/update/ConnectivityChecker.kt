package de.codevoid.aWayToGo.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// ── ConnectivityChecker ────────────────────────────────────────────────────────
//
// Stateless utility for querying whether the device currently has a stable,
// validated internet connection.
//
// Uses NET_CAPABILITY_VALIDATED (not just NET_CAPABILITY_INTERNET) so we don't
// attempt downloads on captive portals (hotel/airport Wi-Fi login screens) where
// the device has a network but no real internet path yet.
//
// The optional bandwidth floor further prevents wasting time on extremely weak
// links where a download would stall in practice.
//
// This object is intentionally framework-free and lives in the update/ package
// so it can be reused by future sync and map-download operations.

object ConnectivityChecker {

    /**
     * Returns true when the active network has been validated by Android
     * (NET_CAPABILITY_VALIDATED — real internet, no captive portal) and has at
     * least [minBandwidthKbps] downstream bandwidth as estimated by the OS.
     *
     * Pass [minBandwidthKbps] = 0 to skip the bandwidth floor entirely.
     */
    fun isStableOnline(context: Context, minBandwidthKbps: Int = 128): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            && caps.linkDownstreamBandwidthKbps >= minBandwidthKbps
    }

    /** Returns true only when the active validated network is a Wi-Fi link. */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
