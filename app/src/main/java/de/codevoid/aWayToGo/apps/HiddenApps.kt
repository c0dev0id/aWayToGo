package de.codevoid.aWayToGo.apps

import android.content.SharedPreferences
import org.json.JSONArray

private const val KEY_HIDDEN = "hidden_packages"

class HiddenApps(private val prefs: SharedPreferences) {

    fun hide(packageName: String) {
        val set = getHidden().toMutableSet()
        set.add(packageName)
        save(set)
    }

    fun show(packageName: String) {
        val set = getHidden().toMutableSet()
        set.remove(packageName)
        save(set)
    }

    fun isHidden(packageName: String): Boolean = getHidden().contains(packageName)

    fun getHidden(): Set<String> {
        val json = prefs.getString(KEY_HIDDEN, null) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            buildSet { for (i in 0 until arr.length()) add(arr.getString(i)) }
        } catch (_: Exception) { emptySet() }
    }

    private fun save(set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        prefs.edit().putString(KEY_HIDDEN, arr.toString()).apply()
    }
}
