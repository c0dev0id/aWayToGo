package de.codevoid.aWayToGo.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

class GeocodingRepository {

    private val client = OkHttpClient()

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search" +
            "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&format=json&limit=10&addressdetails=0"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "de.codevoid.aWayToGo")
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            response.body?.string() ?: return@withContext emptyList()
        }

        val array = JSONArray(body)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(SearchResult(
                    displayName = obj.getString("display_name"),
                    lat = obj.getString("lat").toDouble(),
                    lon = obj.getString("lon").toDouble(),
                ))
            }
        }
    }
}
