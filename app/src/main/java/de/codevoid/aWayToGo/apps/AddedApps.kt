package de.codevoid.aWayToGo.apps

import android.content.SharedPreferences
import org.json.JSONArray

private const val KEY_ADDED = "added_packages"

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

    private fun save(set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs.edit().putString(KEY_ADDED, arr.toString()).apply()
    }
}
