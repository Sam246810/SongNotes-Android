package com.songnotes.core.audio

import kotlinx.coroutines.delay

/**
 * Rule I from the plan: the *only* audio surface the calibration wizard's
 * ViewModel is given. Exactly two operations, no reference to
 * [AudioEngine]'s metronome/transport methods (`armRecording`,
 * `startTestTone`, etc.) at all — the wizard is architecturally incapable
 * of scheduling a competing click no matter what a future edit does to it,
 * not just prevented by discipline or code review. [RealCalibrationAudio]
 * is the only production implementation; [FakeCalibrationAudio] is the
 * test double Rule I's "a fake throws on any unexpected call" refers to.
 */
interface CalibrationAudio {
    /** Runs [repetitionCount] real sweep captures through the duplex engine and returns the aggregated result. */
    suspend fun runSweeps(repetitionCount: Int): CalibrationSession.Result

    /**
     * Plays a single pre-mixed buffer (Rule A — see
     * [Calibration.buildPreMixedVerificationBuffer]) start to finish,
     * suspending until playback completes. No count-in (Rule B): the
     * buffer already contains everything that will play.
     */
    suspend fun playPreMixed(buffer: FloatArray)
}

/** Production [CalibrationAudio], backed by the real duplex engine. */
class RealCalibrationAudio(private val engine: AudioEngine) : CalibrationAudio {
    override suspend fun runSweeps(repetitionCount: Int): CalibrationSession.Result =
        CalibrationSession(engine).run(repetitionCount)

    override suspend fun playPreMixed(buffer: FloatArray) {
        if (!engine.startPlaybackFromBuffer(buffer)) return
        while (engine.state().isPlaying) {
            delay(50)
        }
    }
}
