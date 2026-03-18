package de.codevoid.aWayToGo.apps

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

private const val KEY_ADDED        = "added_packages"
private const val KEY_CUSTOM_NAMES = "custom_names"

class AddedApps(private val prefs: SharedPreferences) {

    fun add(packageName: String) {
        val set = getAdded().toMutableSet()
        set.add(packageName)
        save(set)
    }

    fun remove(packageName: String) {
        val set = getAdded().toMutableSet()
        set.remove(packageName)
        save(set)
        // Also clear any custom name so it does not dangle.
        setCustomName(packageName, null)
    }

    fun setAll(packages: Set<String>) {
        save(packages)
    }

    fun isAdded(packageName: String): Boolean = getAdded().contains(packageName)

    fun getAdded(): Set<String> {
        val json = prefs.getString(KEY_ADDED, null) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        } catch (_: Exception) { emptySet() }
    }

    // ── Custom display name ────────────────────────────────────────────────────

    /**
     * Set a custom display name for [packageName].
     * Pass [name] = null to clear (reset to system label).
     */
    fun setCustomName(packageName: String, name: String?) {
        val map = getCustomNames().toMutableMap()
        if (name == null) map.remove(packageName) else map[packageName] = name
        saveCustomNames(map)
    }

    /** Returns the stored custom name for [packageName], or null if none is set. */
    fun getCustomName(packageName: String): String? = getCustomNames()[packageName]

    private fun getCustomNames(): Map<String, String> {
        val json = prefs.getString(KEY_CUSTOM_NAMES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveCustomNames(map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_CUSTOM_NAMES, obj.toString()).apply()
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun save(set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs.edit().putString(KEY_ADDED, arr.toString()).apply()
    }
}
