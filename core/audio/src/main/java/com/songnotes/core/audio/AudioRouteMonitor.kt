package com.songnotes.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live route-change notifications, backed by `AudioManager`'s
 * `AudioDeviceCallback` push notifications — the piece
 * [AudioRouteDetector]'s own doc comment flagged as still open: "not
 * backed by `AudioDeviceCallback` push-notifications yet ... Live
 * route-change notification (for e.g. invalidating a displayed
 * calibration mid-screen) is still open."
 *
 * [currentRoute] starts as [AudioRouteDetector.currentInputRoute]'s
 * point-in-time result (same heuristic, same `routeKey` scheme — nothing
 * about WHAT counts as "the route" changes here, only WHEN callers find
 * out it changed) and updates whenever `AudioManager` reports an input
 * device being added or removed. A collector (e.g. a Composable via
 * `collectAsState()`) sees every update automatically; nothing needs to
 * re-query on a timer or re-check on every screen visit.
 *
 * **Not verified on a physical device yet.** Registering/unregistering
 * `AudioDeviceCallback` compiles and type-checks against the SDK, but
 * whether it actually fires correctly on a real route change (plugging in
 * headphones, connecting Bluetooth) — and how quickly, and whether
 * `getDevices()` reflects the new state by the time the callback fires —
 * has not been exercised. See `docs/handoff/PHASE-03.md`'s "Known risks"
 * for this specific gap; the next device session should verify this
 * before trusting it in a real user-facing flow beyond the diagnostic use
 * this phase wires it into.
 */
class AudioRouteMonitor(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val detector = AudioRouteDetector(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _currentRoute = MutableStateFlow(detector.currentInputRoute())

    /** The most recently observed route. Reflects [start]'s registration state — see its own doc comment. */
    val currentRoute: StateFlow<AudioRoute> = _currentRoute

    private var callback: AudioDeviceCallback? = null

    /**
     * Registers the underlying `AudioDeviceCallback` and immediately
     * refreshes [currentRoute] (in case the route changed between
     * construction and this call). Safe to call more than once — a second
     * call while already started is a no-op, not a double-registration.
     * Callers own the matching [stop] call (e.g. from a Composable's
     * `DisposableEffect`), same as any other Android system-callback
     * registration — this class never unregisters itself.
     */
    fun start() {
        if (callback != null) {
            _currentRoute.value = detector.currentInputRoute()
            return
        }
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
                _currentRoute.value = detector.currentInputRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
                _currentRoute.value = detector.currentInputRoute()
            }
        }
        audioManager.registerAudioDeviceCallback(cb, mainHandler)
        callback = cb
        _currentRoute.value = detector.currentInputRoute()
    }

    /** Unregisters the callback. Safe to call even if [start] was never called, or called more than once. */
    fun stop() {
        callback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        callback = null
    }
}
