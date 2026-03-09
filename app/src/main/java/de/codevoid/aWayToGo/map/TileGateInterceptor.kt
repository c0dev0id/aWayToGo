package de.codevoid.aWayToGo.map

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * OkHttp **network** interceptor that pauses tile network fetches while the
 * map camera is moving.
 *
 * Why a network interceptor, not an application interceptor?
 *   OkHttp processes the cache *before* network interceptors run.  Tiles
 *   that are already on disk are served from the OkHttp [Cache] immediately,
 *   without ever reaching this interceptor.  Only genuine over-the-wire
 *   fetches are gated.  The result: previously-cached tiles keep rendering
 *   during pan/zoom at full speed, while new network traffic is queued.
 *
 * Only PBF tile requests are gated.  Style JSON, sprites, fonts, and other
 * requests pass through unconditionally so the map can initialise normally.
 *
 * Gate state is set from the main thread; the interceptor runs on OkHttp's
 * dispatcher threads.  [ReentrantLock] + [java.util.concurrent.locks.Condition]
 * give safe cross-thread signalling without busy-waiting.
 */
class TileGateInterceptor : Interceptor {

    @Volatile private var paused = false
    private val lock  = ReentrantLock()
    private val open  = lock.newCondition()

    /**
     * Close the gate.  Subsequent tile network requests will block until
     * [resume] is called.  Already in-flight requests are not affected.
     */
    fun pause() {
        paused = true
    }

    /**
     * Open the gate.  All threads waiting in [intercept] are unblocked and
     * their requests proceed concurrently.
     */
    fun resume() {
        lock.withLock {
            paused = false
            open.signalAll()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (paused && isTileRequest(chain.request().url.toString())) {
            lock.withLock {
                // Wait for the gate to open.  The 2 s safety timeout prevents
                // MapLibre's tile-loading threads from blocking forever if an
                // idle event is somehow missed.
                while (paused) open.await(2_000, TimeUnit.MILLISECONDS)
            }
        }
        return chain.proceed(chain.request())
    }

    /** Returns true for PBF vector tile requests that should be gated. */
    private fun isTileRequest(url: String): Boolean =
        url.contains(".pbf") || url.contains("/tiles/")
}
