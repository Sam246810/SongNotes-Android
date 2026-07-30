package com.songnotes.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay

/**
 * Records a short demo take through [AudioEngine.armRecording] directly
 * (complete with its normal audible metronome — see
 * `CalibrationWizardScreen`'s own doc comment for why this doesn't violate
 * Rule I: recording a demo take isn't calibration *measurement*), then
 * builds a Rule A/B/C pre-mixed verification buffer from it and plays it
 * back through [CalibrationAudio.playPreMixed] exclusively.
 *
 * Shared by both the auto-calibration wizard's "Verify" step and the
 * manual slider fallback — both need exactly this record-then-play-back
 * loop, differing only in where [calibrationOffsetFrames] comes from (a
 * measured [CalibrationSession.Result] vs. a user-dragged slider value).
 * Foreground-service start/stop is deliberately the caller's job, not
 * this function's — that's Android-framework UI-layer bookkeeping, not
 * audio business logic.
 */
object VerificationTakeRecorder {

    suspend fun recordAndPlayVerification(
        engine: AudioEngine,
        calibrationAudio: CalibrationAudio,
        takeFile: File,
        calibrationOffsetFrames: Double,
        bpm: Double = 80.0,
        beatsPerBar: Int = 4,
        countInBeats: Int = 4,
        recordBeats: Int = 8,
        sampleRate: Double = 48000.0,
        onProgress: (elapsedSeconds: Int, totalSeconds: Int) -> Unit = { _, _ -> },
        onPlaybackStart: () -> Unit = {},
    ): Boolean {
        val countInSeconds = countInBeats * 60.0 / bpm
        val recordSeconds = recordBeats * 60.0 / bpm
        val totalSeconds = countInSeconds + recordSeconds

        val armed = engine.armRecording(
            takeFile.absolutePath, bpm, beatsPerBar, countInBeats, calibrationOffsetFrames,
        )
        if (!armed) return false

        val startTimeMs = System.currentTimeMillis()
        while (true) {
            val elapsedSeconds = (System.currentTimeMillis() - startTimeMs) / 1000.0
            onProgress(elapsedSeconds.toInt(), totalSeconds.toInt())
            if (elapsedSeconds >= totalSeconds) break
            delay(150)
        }
        engine.stopRecording()

        val takeBytes = takeFile.readBytes()
        if (takeBytes.isEmpty()) return false
        val take = FloatArray(takeBytes.size / 4)
        ByteBuffer.wrap(takeBytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(take)
        if (take.isEmpty()) return false

        val mixed = Calibration.buildPreMixedVerificationBuffer(
            take = take, sampleRate = sampleRate, bpm = bpm, beatsPerBar = beatsPerBar,
        )
        onPlaybackStart()
        calibrationAudio.playPreMixed(mixed)
        return true
    }
}
