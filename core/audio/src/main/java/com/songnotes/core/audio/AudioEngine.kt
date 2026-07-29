package com.songnotes.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin facade over the native (Oboe-based) duplex audio engine.
 *
 * One output stream owns the data callback; a second input stream is read
 * non-blockingly from inside it (Oboe's FullDuplexStream pattern). The test
 * tone, recording, and playback are three different things that single
 * callback can do — never three different stream configurations — per the
 * plan's "same engine path for calibration and real recording" principle.
 *
 * [state] polls a direct `ByteBuffer` written by the native side with plain
 * atomic stores; reading it from Kotlin costs zero JNI calls beyond the one
 * `nativeGetStateBuffer` call made at engine creation. Fine to poll at UI
 * frame rate.
 *
 * Every `external fun` here is implemented in
 * `core/audio/src/main/cpp/jni_bridge.cpp`.
 */
class AudioEngine {

    private var handle: Long = 0L
    private var stateBuffer: ByteBuffer? = null

    private fun ensureCreated(): Boolean {
        if (handle == 0L) {
            handle = nativeCreate()
            if (handle != 0L) {
                stateBuffer = nativeGetStateBuffer(handle)?.order(ByteOrder.nativeOrder())
            }
        }
        return handle != 0L
    }

    /**
     * Opens the duplex streams without starting any particular mode. Call
     * before [inputSessionId] when the caller needs a real session ID —
     * e.g. to set up [CalibrationAudioEffects] — before the capture that
     * will use it actually starts.
     */
    fun ensureReady(): Boolean = ensureCreated() && nativeEnsureReady(handle)

    /** The input stream's allocated session ID, for [CalibrationAudioEffects]. Call [ensureReady] first. */
    fun inputSessionId(): Int {
        val h = handle
        return if (h == 0L) -1 else nativeGetInputSessionId(h)
    }

    fun startTestTone(): Boolean = ensureCreated() && nativeStartTestTone(handle)

    fun stopTestTone() {
        if (handle != 0L) nativeStopTestTone(handle)
    }

    /**
     * Begins count-in (countInBeats beats at bpm/beatsPerBar), then starts
     * actually recording at the downbeat. [filePath] should live under the
     * app's own storage (e.g. `context.filesDir`) — no permission-scoped
     * storage handling here yet.
     */
    fun armRecording(filePath: String, bpm: Double, beatsPerBar: Int, countInBeats: Int): Boolean =
        ensureCreated() && nativeArmRecording(handle, filePath, bpm, beatsPerBar, countInBeats)

    fun stopRecording() {
        if (handle != 0L) nativeStopRecording(handle)
    }

    fun startPlayback(filePath: String): Boolean = ensureCreated() && nativeStartPlayback(handle, filePath)

    fun stopPlayback() {
        if (handle != 0L) nativeStopPlayback(handle)
    }

    /**
     * Phase 3 calibration: plays [sweep] out through the same duplex engine
     * used for everything else, capturing the acoustic/electrical loopback
     * for `sweep.size + tailPaddingFrames` frames total. Poll [state] for
     * [EngineState.isCalibrating] to drop back to false, then call
     * [takeCalibrationCapture] to retrieve the recording and feed it to
     * [Calibration.measureRoundTripDelay].
     */
    fun startCalibrationCapture(sweep: FloatArray, tailPaddingFrames: Int): Boolean =
        ensureCreated() && nativeStartCalibrationCapture(handle, sweep, tailPaddingFrames)

    /** Aborts an in-flight calibration capture — discards it, not a stop-early-but-keep-what-you-have. */
    fun stopCalibration() {
        if (handle != 0L) nativeStopCalibration(handle)
    }

    /**
     * Retrieves the captured loopback recording after a calibration capture
     * completes naturally (i.e. [EngineState.isCalibrating] observed
     * dropping to false on its own, not via [stopCalibration]). Returns an
     * empty array if called before completion or after an abort.
     */
    fun takeCalibrationCapture(): FloatArray {
        val h = handle
        return if (h == 0L) FloatArray(0) else nativeTakeCalibrationCapture(h)
    }

    /** Stops everything except an in-progress recording — call when the app backgrounds. The mic foreground service is what keeps a recording alive past that point. */
    fun pauseForBackground() {
        if (handle == 0L) return
        nativeStopTestTone(handle)
        nativeStopPlayback(handle)
        nativeStopCalibration(handle)
    }

    fun capabilities(): EngineCapabilities {
        val h = handle
        if (h == 0L) return EngineCapabilities.unavailable("Engine not started")
        return EngineCapabilities(
            audioApi = nativeGetAudioApi(h),
            sampleRate = nativeGetSampleRate(h),
            framesPerBurst = nativeGetFramesPerBurst(h),
            channelCount = nativeGetChannelCount(h),
            format = nativeGetFormat(h),
            sharingMode = nativeGetSharingMode(h),
            performanceMode = nativeGetPerformanceMode(h),
            isMMapUsed = nativeIsMMapUsed(h),
            xRunCount = nativeGetXRunCount(h),
            lastError = nativeGetLastError(h).ifEmpty { null },
        )
    }

    /** Reads the live engine state (recording/playback progress, xruns, dropped frames) with no JNI call. */
    fun state(): EngineState {
        val buf = stateBuffer ?: return EngineState.idle()
        return EngineState(
            isRecording = buf.getInt(EngineState.OFFSET_IS_RECORDING) != 0,
            isPlaying = buf.getInt(EngineState.OFFSET_IS_PLAYING) != 0,
            framesRecorded = buf.getInt(EngineState.OFFSET_FRAMES_RECORDED),
            playbackFrame = buf.getInt(EngineState.OFFSET_PLAYBACK_FRAME),
            playbackTotalFrames = buf.getInt(EngineState.OFFSET_PLAYBACK_TOTAL_FRAMES),
            xRunCount = buf.getInt(EngineState.OFFSET_XRUN_COUNT),
            framesDropped = buf.getInt(EngineState.OFFSET_FRAMES_DROPPED),
            isArmed = buf.getInt(EngineState.OFFSET_IS_ARMED) != 0,
            countInBeatsRemaining = buf.getInt(EngineState.OFFSET_COUNT_IN_BEATS_REMAINING),
            isCalibrating = buf.getInt(EngineState.OFFSET_IS_CALIBRATING) != 0,
            calibrationFramesCaptured = buf.getInt(EngineState.OFFSET_CALIBRATION_FRAMES_CAPTURED),
        )
    }

    /** Releases the native engine. Safe to call more than once; must be called from onDestroy. */
    fun release() {
        val h = handle
        if (h != 0L) {
            nativeDestroy(h)
            handle = 0L
            stateBuffer = null
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeEnsureReady(handle: Long): Boolean
    private external fun nativeGetInputSessionId(handle: Long): Int
    private external fun nativeStartTestTone(handle: Long): Boolean
    private external fun nativeStopTestTone(handle: Long)
    private external fun nativeArmRecording(
        handle: Long,
        filePath: String,
        bpm: Double,
        beatsPerBar: Int,
        countInBeats: Int,
    ): Boolean
    private external fun nativeStopRecording(handle: Long)
    private external fun nativeStartPlayback(handle: Long, filePath: String): Boolean
    private external fun nativeStopPlayback(handle: Long)
    private external fun nativeStartCalibrationCapture(
        handle: Long,
        sweep: FloatArray,
        tailPaddingFrames: Int,
    ): Boolean
    private external fun nativeStopCalibration(handle: Long)
    private external fun nativeTakeCalibrationCapture(handle: Long): FloatArray
    private external fun nativeGetStateBuffer(handle: Long): ByteBuffer?
    private external fun nativeGetAudioApi(handle: Long): String
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeGetFramesPerBurst(handle: Long): Int
    private external fun nativeGetChannelCount(handle: Long): Int
    private external fun nativeGetFormat(handle: Long): String
    private external fun nativeGetSharingMode(handle: Long): String
    private external fun nativeGetPerformanceMode(handle: Long): String
    private external fun nativeIsMMapUsed(handle: Long): Boolean
    private external fun nativeGetXRunCount(handle: Long): Int
    private external fun nativeGetLastError(handle: Long): String

    companion object {
        init {
            System.loadLibrary("songnotes_audio")
        }
    }
}
