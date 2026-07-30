package com.songnotes.core.audio

import kotlinx.coroutines.delay

/**
 * Runs N repetitions of a calibration sweep capture through [engine],
 * aggregates the recovered delays via MAD outlier rejection, and checks for
 * the plan's specific AEC-defeat signature: "PNR high on rep 1, collapsed
 * by rep 5" — a device whose hardware/driver-level AEC can't actually be
 * disabled (see [CalibrationAudioEffects]'s own caveat about this) will
 * often still let an early repetition through relatively clean, then
 * adaptively learn to cancel the sweep as "echo" on later ones, since from
 * the AEC's perspective a repeated, predictable sweep bleeding into the mic
 * looks exactly like the kind of self-echo it exists to suppress.
 *
 * Not wired into any UI yet beyond `DiagnosticsScreen`'s verification
 * section — this is the reusable business-logic layer the eventual wizard
 * (see docs/handoff/PHASE-03.md's "What's left") will call directly.
 */
class CalibrationSession(private val engine: AudioEngine, private val sampleRate: Double = 48000.0) {

    data class Repetition(val delayFrames: Double, val pnrDb: Double)

    data class Result(
        val repetitions: List<Repetition>,
        /** Delays remaining after MAD outlier rejection — the trustworthy subset. */
        val acceptedDelayFrames: List<Double>,
        val meanAcceptedDelayFrames: Double,
        val spreadFrames: Double,
        val aecDefeatSuspected: Boolean,
        val effectsStatus: CalibrationAudioEffects.Status,
    )

    /**
     * The plan's own framing ("PNR high on rep 1, collapsed by rep 5")
     * compares the first repetition against the last, not a smoothed trend
     * — reasoning-based threshold, not measured against a real
     * AEC-can't-be-disabled device (none available to test against here).
     * A genuinely clean measurement run's PNR should vary by a few dB
     * between reps from ordinary room-noise fluctuation, not by 15dB+.
     */
    private val aecDefeatPnrDropThresholdDb = 15.0

    suspend fun run(
        repetitionCount: Int = 5,
        sweepLengthSeconds: Double = 0.5,
        onRepetitionComplete: (index: Int, total: Int, repetition: Repetition) -> Unit = { _, _, _ -> },
    ): Result {
        require(repetitionCount >= 1) { "repetitionCount must be >= 1" }

        engine.ensureReady()
        val effects = CalibrationAudioEffects(engine.inputSessionId())

        // One sweep, reused across every repetition — the signal itself
        // doesn't need to vary between reps, only the acoustic path's
        // response to it does.
        val sweepData = Calibration.generateSweepAndInverse(
            sampleRate = sampleRate, f1Hz = 200.0, f2Hz = 8000.0, lengthSeconds = sweepLengthSeconds, amplitude = 0.7f,
        )
        val tailPaddingFrames = (sampleRate * 0.5).toInt() // 0.5s of room for the round trip + reverb tail

        val repetitions = mutableListOf<Repetition>()
        try {
            for (i in 0 until repetitionCount) {
                if (!engine.startCalibrationCapture(sweepData.sweep, tailPaddingFrames)) break
                while (engine.state().isCalibrating) {
                    delay(100)
                }
                val recording = engine.takeCalibrationCapture()
                if (recording.isEmpty()) continue
                val measurement = Calibration.measureRoundTripDelay(
                    recording = recording, inverseFilter = sweepData.inverseFilter, sweepLength = sweepData.sweep.size,
                )
                val repetition = Repetition(measurement.frames, measurement.pnrDb)
                repetitions.add(repetition)
                // Fired after this repetition's capture has genuinely
                // completed (not a fixed-interval UI timer) — real-time
                // feedback for Rule D's visual/haptic cueing during the
                // wizard's running step.
                onRepetitionComplete(i, repetitionCount, repetition)
            }
        } finally {
            effects.release()
        }

        val delaysArray = repetitions.map { it.delayFrames }.toDoubleArray()
        val accepted = Calibration.rejectOutliersMad(delaysArray).toList()
        val meanAccepted = if (accepted.isEmpty()) Double.NaN else accepted.sum() / accepted.size
        val spread = if (accepted.isEmpty()) Double.NaN else (accepted.max() - accepted.min())

        val aecDefeatSuspected = repetitions.size >= 2 &&
            (repetitions.first().pnrDb - repetitions.last().pnrDb) > aecDefeatPnrDropThresholdDb

        return Result(
            repetitions = repetitions,
            acceptedDelayFrames = accepted,
            meanAcceptedDelayFrames = meanAccepted,
            spreadFrames = spread,
            aecDefeatSuspected = aecDefeatSuspected,
            effectsStatus = effects.status,
        )
    }
}
