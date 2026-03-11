package de.codevoid.aWayToGo.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import de.codevoid.aWayToGo.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
            val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connect()
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

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
            // Remember which URL this partial belongs to.
            urlFile.writeText(url)

            val existing = if (partFile.exists()) partFile.length() else 0L

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            if (existing > 0) {
                conn.setRequestProperty("Range", "bytes=$existing-")
            }
            conn.connect()

            val resumed = existing > 0 && conn.responseCode == 206
            val serverBytes = conn.contentLengthLong          // bytes the server will send
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

            conn.inputStream.use { input ->
                FileOutputStream(partFile, resumed).use { output ->
                    val buf = ByteArray(65_536)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        ensureActive()                        // honour cancellation
                        output.write(buf, 0, read)
                        downloaded += read
                        speedBytes += read

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

            partFile.renameTo(apkFile)
            urlFile.delete()
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
