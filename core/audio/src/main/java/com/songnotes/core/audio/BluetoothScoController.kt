package com.songnotes.core.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Negotiates Bluetooth SCO — the low-quality, call-audio profile, and the
 * ONLY way an Android app gets a real microphone input path from a
 * Bluetooth device. Plain A2DP (music/media) Bluetooth audio is
 * output-only: a device connected only via A2DP never becomes a usable
 * input no matter what device ID gets passed to
 * `AudioStreamBuilder.setDeviceId()` — [AudioEngine.setPreferredInputDevice]
 * alone can't make Bluetooth mic input work, only choose *which* already-
 * routable device to use. Starting SCO is what actually makes
 * `AudioManager` offer the headset as an input at all, and it downgrades
 * BOTH directions to narrowband (~8kHz) call-quality audio while active —
 * a real, visible tradeoff, not a free upgrade, which is why this is an
 * explicit opt-in (see `ScratchpadScreen`'s mic-routing checkbox) rather
 * than something the engine reaches for automatically whenever a
 * Bluetooth device is connected.
 *
 * Requires `MODIFY_AUDIO_SETTINGS` (normal, declared in the manifest).
 * `BLUETOOTH_CONNECT` is declared too for devices/OS versions that check
 * it, but deliberately never requested through Android's runtime
 * permission-request machinery here — routing a BLUETOOTH_CONNECT request
 * through `ActivityResultContracts.RequestPermission()` on this app's
 * `FragmentActivity` reliably crashed with `IllegalArgumentException: Can
 * only use lower 16 bits for requestCode` (`ActivityResultRegistry`'s
 * internal request-code generation vs. `FragmentActivity`'s legacy
 * 16-bit-only validation — a known androidx interop gap, not something
 * fixable from this app's code). [connect] instead just attempts SCO
 * directly and catches `SecurityException` if a device genuinely enforces
 * the permission, failing this one connect attempt rather than crashing —
 * see `ScratchpadScreen`'s status message on a false return.
 *
 * Two entirely different implementations depending on API level, not one
 * with a version check sprinkled in — they don't share a confirmation
 * mechanism. API 31+ uses `setCommunicationDevice()`, which reports
 * success or failure *synchronously* via its own return value — no
 * broadcast, no polling, nothing to race or time out. Below that (this
 * app's minSdk is 30), the only available API is the deprecated
 * `startBluetoothSco()` pair, which is asynchronous and has to be
 * confirmed via the `ACTION_SCO_AUDIO_STATE_UPDATED` broadcast — and on
 * a real Samsung device that broadcast never fired with CONNECTED at
 * all, even though the system-level route switched to Bluetooth SCO in
 * well under a second (confirmed via `adb logcat`'s own audio-framework
 * logs). Mixing the two APIs (legacy start + modern poll) was tried
 * first and was *less* reliable than either alone, not more — Google's
 * own guidance is to use one full API family or the other, never both.
 */
class BluetoothScoController(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Requests SCO and suspends until connected, or [timeoutMs] elapses
     * (API 30 only — see the class doc comment; API 31+'s
     * `setCommunicationDevice()` returns immediately, no timeout needed).
     * Returns `false` (never throws) on failure, including a
     * `SecurityException` from a missing `BLUETOOTH_CONNECT` grant — see
     * the class doc comment for why that's never proactively requested.
     */
    suspend fun connect(timeoutMs: Long = 5000L): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) connectModern() else connectLegacy(timeoutMs)

    /** Tears SCO back down — see [connect]'s and the class's doc comments for why this always matters. */
    fun disconnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION") // see connectLegacy()'s comment on startBluetoothSco()
            audioManager.stopBluetoothSco()
        }
    }

    /**
     * `setCommunicationDevice()` picks from whatever the system already
     * lists as *available* for communication use — a paired, in-range
     * Bluetooth device that supports the SCO/HFP profile shows up here as
     * a `TYPE_BLUETOOTH_SCO` candidate even before any session is active
     * (the same way it already appeared in a `GET_DEVICES_INPUTS` query,
     * per [AudioRouteDetector]) — this call is what actually negotiates
     * the connection, not just declares intent to.
     */
    private fun connectModern(): Boolean {
        val scoDevice = audioManager.availableCommunicationDevices
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: return false
        return try {
            audioManager.setCommunicationDevice(scoDevice)
        } catch (e: SecurityException) {
            false
        }
    }

    private suspend fun connectLegacy(timeoutMs: Long): Boolean {
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> result.complete(true)
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED, AudioManager.SCO_AUDIO_STATE_ERROR ->
                        result.complete(false)
                    // SCO_AUDIO_STATE_CONNECTING: not a final state, keep waiting for the next broadcast.
                }
            }
        }
        @Suppress("UnspecifiedRegisterReceiverFlag") // API 30 only — RECEIVER_NOT_EXPORTED doesn't exist yet
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))

        return try {
            audioManager.isBluetoothScoOn = true
            // Deprecated in favor of setCommunicationDevice() (API 31+, see connectModern()),
            // but that has no equivalent on API 30 — this is the only path that exists there.
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            withTimeoutOrNull(timeoutMs) { result.await() } ?: false
        } catch (e: SecurityException) {
            false
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
