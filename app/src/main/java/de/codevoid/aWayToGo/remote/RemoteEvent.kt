package de.codevoid.aWayToGo.remote

/**
 * Events emitted by [RemoteControlManager].
 *
 * All keys emit [KeyDown] / [KeyUp] for raw press/release.
 * On release, [ShortPress] or [LongPress] is additionally emitted:
 *   - [LongPress] only for keys listed in RemoteControlManager.LONG_PRESS_KEYS
 *     (currently CONFIRM and BACK) when held >= LONG_PRESS_THRESHOLD_MS.
 *   - [ShortPress] for everything else.
 *
 * When the remote has a physical joystick it sends [JoyInput] instead of
 * directional [KeyDown]/[KeyUp] events.  dx/dy are normalised to [-1, 1];
 * (0, 0) means the joystick was released (neutral position).
 */
sealed class RemoteEvent {
    data class KeyDown(val key: RemoteKey) : RemoteEvent()
    data class KeyUp(val key: RemoteKey) : RemoteEvent()
    data class ShortPress(val key: RemoteKey) : RemoteEvent()
    data class LongPress(val key: RemoteKey) : RemoteEvent()
    data class JoyInput(val dx: Float, val dy: Float) : RemoteEvent()
}
