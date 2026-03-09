package de.codevoid.aWayToGo.remote

/**
 * Events emitted by [RemoteControlManager].
 *
 * All keys emit [KeyDown] / [KeyUp] for raw press/release.
 * On release, [ShortPress] or [LongPress] is additionally emitted:
 *   - [LongPress] only for keys listed in RemoteControlManager.LONG_PRESS_KEYS
 *     (currently CONFIRM and BACK) when held >= LONG_PRESS_THRESHOLD_MS.
 *   - [ShortPress] for everything else.
 */
sealed class RemoteEvent {
    data class KeyDown(val key: RemoteKey) : RemoteEvent()
    data class KeyUp(val key: RemoteKey) : RemoteEvent()
    data class ShortPress(val key: RemoteKey) : RemoteEvent()
    data class LongPress(val key: RemoteKey) : RemoteEvent()
}
