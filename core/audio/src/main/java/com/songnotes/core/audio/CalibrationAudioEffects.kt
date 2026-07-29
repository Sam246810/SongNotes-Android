package com.songnotes.core.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * Requests AEC/NS/AGC be disabled on the given input session for the
 * duration of a calibration capture. Pure `android.media.audiofx.*` Java
 * API — Oboe and the native engine don't touch this surface at all (see
 * `docs/handoff/PHASE-03.md`'s "What's left" list). [sessionId] should come
 * from [AudioEngine.inputSessionId], which requires the input stream to
 * have been opened with `SessionId.Allocate` (it is, since Phase 3).
 *
 * This only handles the *request* side. On some devices a hardware/driver-
 * level AEC can't actually be bypassed no matter what the app asks for —
 * [status] reports what this API claims happened, not a guarantee the raw
 * audio path is actually clean. Detecting that mismatch is the plan's
 * separate "AEC-defeat detection by convergence signature" heuristic
 * (PNR high on repetition 1, collapsed by repetition 5) — not implemented
 * here; this class is only the disabling attempt that heuristic would sit
 * on top of.
 */
class CalibrationAudioEffects(sessionId: Int) {

    data class Status(
        val aecAvailable: Boolean,
        val aecDisabled: Boolean,
        val nsAvailable: Boolean,
        val nsDisabled: Boolean,
        val agcAvailable: Boolean,
        val agcDisabled: Boolean,
    )

    val status: Status

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    init {
        val aecAvailable = AcousticEchoCanceler.isAvailable()
        val nsAvailable = NoiseSuppressor.isAvailable()
        val agcAvailable = AutomaticGainControl.isAvailable()

        var aecDisabled = false
        var nsDisabled = false
        var agcDisabled = false

        if (aecAvailable) {
            AcousticEchoCanceler.create(sessionId)?.let { effect ->
                effect.setEnabled(false)
                aecDisabled = !effect.enabled // trust the actual reported state, not setEnabled's return code
                aec = effect
            }
        }
        if (nsAvailable) {
            NoiseSuppressor.create(sessionId)?.let { effect ->
                effect.setEnabled(false)
                nsDisabled = !effect.enabled
                ns = effect
            }
        }
        if (agcAvailable) {
            AutomaticGainControl.create(sessionId)?.let { effect ->
                effect.setEnabled(false)
                agcDisabled = !effect.enabled
                agc = effect
            }
        }

        status = Status(
            aecAvailable = aecAvailable,
            aecDisabled = aecDisabled,
            nsAvailable = nsAvailable,
            nsDisabled = nsDisabled,
            agcAvailable = agcAvailable,
            agcDisabled = agcDisabled,
        )
    }

    /** Must be called once the capture using this session is done — these wrap native platform resources. */
    fun release() {
        aec?.release()
        aec = null
        ns?.release()
        ns = null
        agc?.release()
        agc = null
    }
}
