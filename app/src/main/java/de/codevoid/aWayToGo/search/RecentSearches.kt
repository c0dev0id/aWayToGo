package de.codevoid.aWayToGo.search

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

// Only the single most-recent search term is kept; the remaining shortcut-card
// space is reserved for favourite locations and last-navigated destination.
private const val MAX_TERMS     = 1
private const val MAX_LOCATIONS = 5
private const val KEY_TERMS     = "recent_terms"
private const val KEY_LOCATIONS = "recent_locations"

class RecentSearches(private val prefs: SharedPreferences) {

    fun saveSearchTerm(term: String) {
        val list = getSearchTerms().toMutableList()
        list.remove(term)
        list.add(0, term)
        val arr = JSONArray()
        list.take(MAX_TERMS).forEach { arr.put(it) }
        prefs.edit().putString(KEY_TERMS, arr.toString()).apply()
    }

    fun getSearchTerms(): List<String> {
        val json = prefs.getString(KEY_TERMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList { for (i in 0 until arr.length()) add(arr.getString(i)) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveLocation(name: String, lat: Double, lon: Double) {
        val list = getLocations().toMutableList()
        list.removeAll { it.displayName == name }
        list.add(0, SearchResult(name, lat, lon))
        val arr = JSONArray()
        list.take(MAX_LOCATIONS).forEach { r ->
            arr.put(JSONObject().apply {
                put("name", r.displayName)
                put("lat", r.lat)
                put("lon", r.lon)
            })
        }
        prefs.edit().putString(KEY_LOCATIONS, arr.toString()).apply()
    }

    fun getLocations(): List<SearchResult> {
        val json = prefs.getString(KEY_LOCATIONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(SearchResult(
                        displayName = obj.getString("name"),
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon"),
                    ))
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
