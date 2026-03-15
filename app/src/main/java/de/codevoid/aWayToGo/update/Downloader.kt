package de.codevoid.aWayToGo.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// ── DownloadProgress ───────────────────────────────────────────────────────────
//
// Snapshot emitted at most every 0.5 s during an active download.
// Lives here rather than in AppUpdater because it belongs to the generic
// infrastructure layer, not the app-update layer.

data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
)

// ── NoConnectionException ──────────────────────────────────────────────────────

/** Thrown by [Downloader.download] when no stable internet connection is available. */
class NoConnectionException : IOException("No stable internet connection")

// ── Downloader ─────────────────────────────────────────────────────────────────
//
// Generic, robust file downloader built on OkHttp.  All download operations in
// the app go through this class so connectivity guards, resume logic, timeout
// policy, atomic delivery, and stale-file cleanup are applied consistently.
//
// Sidecar file convention (all names derived from destFile):
//   <destFile>       the final, fully downloaded file
//   <destFile>.part  partial download currently in progress
//   <destFile>.url   the URL that the partial belongs to
//
// Callers never need to manage sidecar files directly.

class Downloader(private val context: Context) {

    companion object {
        /** Partial files older than this are considered stale and will be deleted. */
        const val STALE_PART_AGE_MS = 2 * 24 * 60 * 60 * 1_000L   // 2 days
    }

    // connectTimeout: guards the TCP handshake.
    // readTimeout:    fires if no bytes are received for 30 s — primary guard against
    //                 stale / hung connections mid-download.
    // No callTimeout: large files can legitimately take minutes.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun partFile(dest: File) = File(dest.path + ".part")
    private fun urlFile(dest: File)  = File(dest.path + ".url")

    /**
     * Downloads [url] to [destFile] atomically.
     *
     * Behaviour:
     * - **Connectivity pre-check**: throws [NoConnectionException] if
     *   [ConnectivityChecker.isStableOnline] returns false.
     * - **Stale cleanup**: calls [cleanupStale] before starting, so old partials
     *   for a superseded URL or files older than [STALE_PART_AGE_MS] are removed.
     * - **Fast path**: if [destFile] already exists for this URL, it is returned
     *   immediately without any network activity.
     * - **Resume**: sends `Range: bytes=N-` if a valid .part file for this URL exists.
     * - **Atomic delivery**: renames .part → [destFile] only on full success.
     * - **Connection loss**: OkHttp's readTimeout(30 s) throws IOException if the
     *   server stops sending bytes; the .part file is preserved for future resume.
     * - **HTTP errors**: on a non-2xx response the .part / .url sidecars are deleted
     *   (the URL is considered invalid or superseded — resume is pointless).
     * - **Progress**: [onProgress] is invoked on Dispatchers.Main at most every 0.5 s.
     * - **Cancellation**: honoured per read chunk; .part + .url are preserved for resume.
     *
     * @throws NoConnectionException  no stable internet (pre-check failed)
     * @throws CancellationException  coroutine cancelled (partial preserved for resume)
     * @throws IOException            HTTP error or I/O failure
     */
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (!ConnectivityChecker.isStableOnline(context)) throw NoConnectionException()

        val part = partFile(destFile)
        val urlf = urlFile(destFile)

        cleanupStale(url, destFile)

        // Fast path: already fully downloaded for this exact URL.
        if (destFile.exists() && urlf.exists() && urlf.readText().trim() == url) {
            return@withContext destFile
        }

        // Persist the URL so a future session can validate or resume this partial.
        urlf.writeText(url)

        val existing = if (part.exists()) part.length() else 0L

        val request = Request.Builder()
            .url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // Non-2xx → URL is bad or superseded.  Discard partial so a retry starts fresh.
                part.delete(); urlf.delete()
                throw IOException("HTTP ${response.code}")
            }

            val resumed = existing > 0 && response.code == 206
            val serverBytes = response.body?.contentLength() ?: -1L
            val alreadyDownloaded = if (resumed) existing else 0L
            val total = if (serverBytes > 0) alreadyDownloaded + serverBytes else -1L

            // Server ignored Range header (replied 200) → discard any previous partial.
            if (!resumed) part.delete()

            var speedBytes = 0L
            var speedStamp = System.nanoTime()
            var currentSpeed = 0L
            var downloaded = alreadyDownloaded

            val body = response.body
                ?: run { part.delete(); urlf.delete(); throw IOException("Empty response body") }

            try {
                body.source().use { source ->
                    part.sink(append = resumed).buffer().use { sink ->
                        val buf = okio.Buffer()
                        while (true) {
                            ensureActive()                       // honour coroutine cancellation
                            val n = source.read(buf, 65_536)
                            if (n == -1L) break
                            sink.write(buf, n)
                            downloaded += n
                            speedBytes += n

                            val now = System.nanoTime()
                            val elapsed = now - speedStamp
                            if (elapsed >= 500_000_000L) {      // update speed every 0.5 s
                                currentSpeed = speedBytes * 1_000_000_000L / elapsed
                                speedBytes = 0L
                                speedStamp = now

                                if (total > 0) {
                                    val progress = DownloadProgress(downloaded, total, currentSpeed)
                                    withContext(Dispatchers.Main) { onProgress(progress) }
                                }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Partial + url sidecar kept intentionally — allows resume next session.
                throw e
            }
            // IO / network exceptions (readTimeout, etc.) also keep .part for resume.
            // They propagate naturally without reaching the rename below.

            if (!part.renameTo(destFile)) {
                part.delete(); urlf.delete()
                throw IOException("Failed to rename partial download to ${destFile.name}")
            }
            destFile
        }
    }

    /**
     * Removes the .part and .url sidecar files for [destFile] when they are stale:
     * - The .url sidecar records a URL different from [expectedUrl], or
     * - The .part file is older than [STALE_PART_AGE_MS].
     *
     * Also removes a completed [destFile] if its .url sidecar records a different URL,
     * since it was downloaded from a superseded release and should be replaced.
     *
     * Call this after resolving the current download URL so files from previous releases
     * do not accumulate.
     */
    fun cleanupStale(expectedUrl: String?, destFile: File) {
        val part = partFile(destFile)
        val urlf = urlFile(destFile)

        if (part.exists()) {
            val storedUrl = if (urlf.exists()) urlf.readText().trim() else null
            val tooOld    = System.currentTimeMillis() - part.lastModified() > STALE_PART_AGE_MS
            if (storedUrl != expectedUrl || tooOld) {
                part.delete(); urlf.delete()
            }
        }

        if (destFile.exists() && urlf.exists() && urlf.readText().trim() != expectedUrl) {
            destFile.delete(); urlf.delete()
        }
    }
}
