package com.songnotes.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay

/**
 * The plan's manual tap-along path: records a take through
 * [AudioEngine.armRecording] with its normal audible metronome (same
 * "this isn't calibration *measurement* in Rule I's sense, it's ordinary
 * recording" reasoning as [VerificationTakeRecorder]), then — instead of
 * building a pre-mixed verification buffer — runs the recorded take
 * through [Calibration.detectOnsets]/[Calibration.estimateLatencyFromOnsets]
 * to measure the round-trip latency from the user's own physical taps.
 *
 * Scheduled tap times are computed directly from [bpm], not read back
 * from the engine: [calibrationOffsetFrames] is always 0 here (this
 * function is *measuring* the offset, not correcting for one it already
 * knows), so per the plan's "head-skip applied once at commit" design,
 * the stored take's frame 0 is exactly the transport downbeat — the same
 * assumption [VerificationTakeRecorder]'s pre-mixed click regeneration
 * already relies on. Beat `i` (0-indexed from that downbeat) is therefore
 * scheduled at `i * 60/bpm` seconds in the take's own timebase, and that's
 * exactly what the user is meant to tap on, starting with the downbeat
 * itself.
 */
object TapAlongCalibrationRecorder {

    data class Result(
        /** Median measured round-trip latency, or null if too few taps were confidently detected. */
        val estimatedLatencySeconds: Double?,
        val detectedOnsetCount: Int,
        val scheduledTapCount: Int,
    )

    suspend fun recordAndMeasure(
        engine: AudioEngine,
        takeFile: File,
        bpm: Double = 80.0,
        beatsPerBar: Int = 4,
        countInBeats: Int = 4,
        tapBeats: Int = 8,
        sampleRate: Double = 48000.0,
        onProgress: (elapsedSeconds: Int, totalSeconds: Int) -> Unit = { _, _ -> },
    ): Result? {
        val countInSeconds = countInBeats * 60.0 / bpm
        val recordSeconds = tapBeats * 60.0 / bpm
        val totalSeconds = countInSeconds + recordSeconds

        val armed = engine.armRecording(
            takeFile.absolutePath, bpm, beatsPerBar, countInBeats, calibrationOffsetFrames = 0.0,
        )
        if (!armed) return null

        val startTimeMs = System.currentTimeMillis()
        while (true) {
            val elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000.0
            onProgress(elapsedSeconds.toInt(), totalSeconds.toInt())
            if (elapsedSeconds >= totalSeconds) break
            delay(150)
        }
        engine.stopRecording()

        val takeBytes = takeFile.readBytes()
        if (takeBytes.isEmpty()) return null
        val take = FloatArray(takeBytes.size / 4)
        ByteBuffer.wrap(takeBytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(take)
        if (take.isEmpty()) return null

        val detected = Calibration.detectOnsets(take, sampleRate)
        val beatFrames = sampleRate * 60.0 / bpm
        val scheduledTimes = DoubleArray(tapBeats) { i -> (i * beatFrames) / sampleRate }
        val estimate = Calibration.estimateLatencyFromOnsets(detected, scheduledTimes)

        return Result(
            estimatedLatencySeconds = estimate,
            detectedOnsetCount = detected.size,
            scheduledTapCount = tapBeats,
        )
    }
}
