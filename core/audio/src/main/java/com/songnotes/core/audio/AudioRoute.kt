package com.songnotes.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * A route the app might record through — the thing calibration data gets
 * keyed by, since a measured round-trip offset for the built-in
 * speaker+mic has nothing to do with the offset for a pair of Bluetooth
 * headphones. [routeKey] follows the plan's own naming: "device type +
 * product hash".
 */
data class AudioRoute(
    val routeKey: String,
    val label: String,
    val isBluetooth: Boolean,
    /** True when this route IS the phone's own mic — the case where a "use phone mic" override has nothing to do. */
    val isBuiltinMic: Boolean,
)

/**
 * Detects the input route the system will most likely use next, from
 * `AudioManager`'s device list. Not backed by `AudioDeviceCallback`
 * push-notifications yet — [currentInputRoute] is a point-in-time query,
 * good enough for "what route is calibration about to measure/apply to"
 * right before a capture, which is the only place this is called so far.
 * Live route-change notification (for e.g. invalidating a displayed
 * calibration mid-screen) is still open — see docs/handoff/PHASE-03.md.
 */
class AudioRouteDetector(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Best-guess currently-relevant input route. Android doesn't expose
     * "the one device a not-yet-open stream will actually route to"
     * directly pre-API 31 — this applies the OS's usual precedence
     * (wired > Bluetooth > built-in) over whatever `AudioManager` reports
     * as connected, which is a heuristic, not a guarantee. Good enough to
     * decide "should we warn about Bluetooth before calibrating," which is
     * the only thing depending on it right now.
     */
    fun currentInputRoute(): AudioRoute {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val chosen = devices.firstOrNull { isWiredType(it.type) }
            ?: devices.firstOrNull { isBluetoothType(it.type) }
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: devices.firstOrNull()

        return toAudioRoute(chosen)
    }

    /**
     * The phone's own built-in mic's `AudioDeviceInfo.id`, for
     * [AudioEngine.setPreferredInputDevice] — null if this device somehow
     * has none (shouldn't happen on a real phone, but a device list is
     * still just whatever the OS reports right now).
     */
    fun builtinMicDeviceId(): Int? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.id

    private fun toAudioRoute(device: AudioDeviceInfo?): AudioRoute {
        if (device == null) {
            return AudioRoute(routeKey = "unknown", label = "Unknown route", isBluetooth = false, isBuiltinMic = false)
        }
        val productHash = device.productName?.toString()?.hashCode() ?: 0
        return AudioRoute(
            routeKey = "${device.type}_$productHash",
            label = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeLabel(device.type),
            isBluetooth = isBluetoothType(device.type),
            isBuiltinMic = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
        )
    }

    private fun isWiredType(type: Int) = type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_DEVICE

    private fun isBluetoothType(type: Int) =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP

    private fun typeLabel(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call audio)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (media audio)"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        else -> "Audio device (type $type)"
    }
}
