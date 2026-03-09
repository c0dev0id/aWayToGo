package de.codevoid.aWayToGo.remote

/**
 * Keys emitted by DMD remote controllers via the
 * com.thorkracing.wireddevices.keypress broadcast.
 */
enum class RemoteKey(val keyCode: Int) {
    UP(19),        // Map pan up / menu select up
    DOWN(20),      // Map pan down / menu select down
    LEFT(21),      // Map pan left / menu select left
    RIGHT(22),     // Map pan right / menu select right
    CONFIRM(66),   // Round button 1 — follow location / center map (long: toggle tracking)
    BACK(111),     // Round button 2 — cancel / back / tilt toggle (long: reset bearing + tilt)
    ZOOM_IN(136),  // Switch in — zoom in
    ZOOM_OUT(137); // Switch out — zoom out

    companion object {
        private val byKeyCode = entries.associateBy { it.keyCode }
        fun fromKeyCode(code: Int): RemoteKey? = byKeyCode[code]
    }
}
