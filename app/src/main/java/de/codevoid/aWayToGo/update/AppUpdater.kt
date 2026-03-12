package de.codevoid.aWayToGo.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.codevoid.aWayToGo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub for a newer pre-release APK, delegates the actual download to
 * [Downloader], and hands the result to the system package installer.
 *
 * All network and file I/O runs on [Dispatchers.IO] inside suspend functions.
 * The class holds no mutable state — it is safe to use as a singleton.
 *
 * ### Update detection
 * The CI pipeline embeds the short Git commit hash in the APK filename
 * (e.g. `aWayToGo-abc1234.apk`).  The app's own hash is in [BuildConfig.GIT_COMMIT].
 * If the most recent pre-release APK filename contains that hash, the build is current
 * and [checkForUpdate] returns null.  No version-code comparison is needed.
 */
class AppUpdater(private val context: Context) {

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/c0dev0id/aWayToGo/releases"

        /**
         * Extracts the short git hash from a GitHub release APK download URL.
         *
         * Example: `"https://.../aWayToGo-abc1234.apk"` → `"abc1234"`
         *
         * Returns null if the URL does not follow the expected naming convention.
         */
        fun versionHashFromUrl(url: String): String? =
            Regex("""aWayToGo-([0-9a-f]+)\.apk""")
                .find(url.substringAfterLast('/'))
                ?.groupValues?.get(1)
    }

    // Short-lived check call: callTimeout caps the total round-trip so the UI
    // never stalls indefinitely waiting for the GitHub releases API.
    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val downloader = Downloader(context)
    private val apkFile get() = File(context.externalCacheDir, "update.apk")

    /**
     * Fetches the GitHub releases list and returns the browser download URL of the APK
     * from the most recent pre-release, or null if:
     * - The current build is already at that release (hash match), or
     * - No pre-release with an APK asset was found, or
     * - Any network or parse error occurred.
     */
    suspend fun checkForUpdate(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .build()
            val body = checkClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }

            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            var bestTime = 0L
            var bestUrl: String? = null
            var isCurrentBuild = false

            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val rel = releases.getJSONObject(i)
                if (!rel.optBoolean("prerelease")) continue
                val published = fmt.parse(rel.optString("published_at"))?.time ?: continue
                if (published <= bestTime) continue

                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name  = asset.optString("name")
                    if (name.endsWith(".apk")) {
                        bestUrl        = asset.optString("browser_download_url")
                        bestTime       = published
                        isCurrentBuild = name.contains(BuildConfig.GIT_COMMIT)
                        break
                    }
                }
            }

            if (isCurrentBuild) null else bestUrl
        } catch (_: Exception) { null }
    }

    /**
     * Removes partial or completed download files that do not belong to [currentUrl].
     * Delegates to [Downloader.cleanupStale] so the same policy (URL mismatch, 2-day
     * age) is applied here as for all other download operations.
     */
    fun cleanupStaleFiles(currentUrl: String?) {
        downloader.cleanupStale(currentUrl, apkFile)
    }

    /**
     * Downloads the APK at [url] to `externalCacheDir/update.apk`.
     *
     * Delegates entirely to [Downloader.download], which handles connectivity
     * pre-check, resume, atomic swap, and stall detection.
     *
     * [onProgress] is invoked on [Dispatchers.Main] during the download.
     *
     * @throws NoConnectionException  if no stable internet connection is available
     * @throws CancellationException  on coroutine cancellation (partial preserved)
     * @throws Exception              on HTTP errors or I/O failures
     */
    suspend fun downloadApk(url: String, onProgress: (DownloadProgress) -> Unit): File =
        downloader.download(url, apkFile, onProgress)

    /**
     * Opens the downloaded APK file with the system package installer.
     *
     * Requires `REQUEST_INSTALL_PACKAGES` permission and a `<provider>` entry in the
     * manifest with authority `${applicationId}.fileprovider`.
     */
    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
