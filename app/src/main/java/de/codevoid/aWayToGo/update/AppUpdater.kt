package de.codevoid.aWayToGo.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.codevoid.aWayToGo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Progress snapshot emitted during an APK download.
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
)

/**
 * Checks GitHub for a newer pre-release APK, downloads it, and hands it to the
 * system package installer.
 *
 * All network and file I/O runs on [Dispatchers.IO] inside suspend functions.
 * The class holds no state — it is safe to create on demand or inject as a singleton.
 *
 * ### Update detection
 * The CI pipeline embeds the short Git commit hash in the APK filename
 * (e.g. `aWayToGo-abc1234.apk`).  The app's own hash is in [BuildConfig.GIT_COMMIT].
 * If the most recent pre-release APK filename contains that hash, the build is current
 * and [checkForUpdate] returns null.  No timestamp or version-code comparison is needed.
 */
class AppUpdater(private val context: Context) {

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/c0dev0id/aWayToGo/releases"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val partFile get() = File(context.externalCacheDir, "update.apk.part")
    private val apkFile  get() = File(context.externalCacheDir, "update.apk")
    private val urlFile  get() = File(context.externalCacheDir, "update.url")

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
            val body = httpClient.newCall(request).execute().use { response ->
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
     * Removes partial/completed download files that do not belong to [currentUrl].
     * Call after [checkForUpdate] so only the matching partial file is kept.
     */
    fun cleanupStaleFiles(currentUrl: String?) {
        val storedUrl = if (urlFile.exists()) urlFile.readText().trim() else null
        if (storedUrl != currentUrl) {
            partFile.delete()
            urlFile.delete()
            apkFile.delete()
        }
    }

    /**
     * Downloads the APK at [url] to `externalCacheDir/update.apk`.
     *
     * Resumes from a previous partial download if a `.part` file for the same URL exists.
     * [onProgress] is invoked on [Dispatchers.Main] with download size, total, and speed.
     *
     * On cancellation the partial file is retained for later resume.
     *
     * @throws Exception on any network or I/O error — let the caller decide how to surface it.
     */
    suspend fun downloadApk(url: String, onProgress: (DownloadProgress) -> Unit): File =
        withContext(Dispatchers.IO) {
            // If a completed download for this exact URL already exists, reuse it.
            if (apkFile.exists() && urlFile.exists() && urlFile.readText().trim() == url) {
                return@withContext apkFile
            }

            // Remember which URL this partial belongs to.
            urlFile.writeText(url)

            val existing = if (partFile.exists()) partFile.length() else 0L

            val request = Request.Builder()
                .url(url)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 206) {
                throw Exception("HTTP ${response.code}")
            }

            val resumed = existing > 0 && response.code == 206
            val serverBytes = response.body?.contentLength() ?: -1L
            val alreadyDownloaded = if (resumed) existing else 0L
            val total = if (serverBytes > 0) alreadyDownloaded + serverBytes else -1L

            if (!resumed) {
                // Server ignored Range or fresh start — overwrite.
                partFile.delete()
            }

            // Speed tracking: bytes received since last speed sample.
            var speedBytes = 0L
            var speedStamp = System.nanoTime()
            var currentSpeed = 0L

            var downloaded = alreadyDownloaded

            val body = response.body ?: throw Exception("Empty response body")
            body.source().use { source ->
                partFile.sink(append = resumed).buffer().use { sink ->
                    val buf = okio.Buffer()
                    while (true) {
                        ensureActive()                        // honour cancellation
                        val n = source.read(buf, 65_536)
                        if (n == -1L) break
                        sink.write(buf, n)
                        downloaded += n
                        speedBytes += n

                        val now = System.nanoTime()
                        val elapsed = now - speedStamp
                        if (elapsed >= 500_000_000L) {        // update speed every 0.5 s
                            currentSpeed = speedBytes * 1_000_000_000L / elapsed
                            speedBytes = 0L
                            speedStamp = now
                        }

                        if (total > 0) {
                            withContext(Dispatchers.Main) {
                                onProgress(DownloadProgress(downloaded, total, currentSpeed))
                            }
                        }
                    }
                }
            }

            if (!partFile.renameTo(apkFile)) throw java.io.IOException("Failed to rename download")
            apkFile
        }

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
