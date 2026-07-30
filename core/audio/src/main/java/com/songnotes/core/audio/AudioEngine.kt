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
     * Flattens [tracks] into the track-major flat arrays both
     * `nativeStartMultitrackPlayback` and `nativeArmRecording` (backing
     * tracks) expect — see the doc comment on `parseFlatTracks` in
     * `jni_bridge.cpp` for the shape. An empty [tracks] list produces
     * all-empty arrays, which both native functions treat as "no tracks."
     */
    private fun flattenTracks(tracks: List<MultitrackTrackSpec>): FlattenedTracks {
        val totalClips = tracks.sumOf { it.clips.size }
        val clipBuffers: Array<FloatArray> = Array(totalClips) { FloatArray(0) }
        val clipStartFrames = LongArray(totalClips)
        val clipBufferOffsetFrames = LongArray(totalClips)
        val clipLengthFrames = LongArray(totalClips)
        val trackClipCounts = IntArray(tracks.size)
        val trackGains = FloatArray(tracks.size)
        val trackMuted = BooleanArray(tracks.size)
        val trackSoloed = BooleanArray(tracks.size)
        var flatIndex = 0
        tracks.forEachIndexed { trackIndex, track ->
            trackClipCounts[trackIndex] = track.clips.size
            trackGains[trackIndex] = track.gain
            trackMuted[trackIndex] = track.muted
            trackSoloed[trackIndex] = track.soloed
            for (clip in track.clips) {
                clipBuffers[flatIndex] = clip.buffer
                clipStartFrames[flatIndex] = clip.startFrame
                clipBufferOffsetFrames[flatIndex] = clip.bufferOffsetFrames
                clipLengthFrames[flatIndex] = clip.lengthFrames
                flatIndex++
            }
        }
        return FlattenedTracks(
            clipBuffers, clipStartFrames, clipBufferOffsetFrames, clipLengthFrames, trackClipCounts,
            trackGains, trackMuted, trackSoloed,
        )
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
     *
     * [calibrationOffsetFrames] (Rule C) should come from
     * `CalibrationStore.load(route.routeKey)?.offsetFrames` for whatever
     * route this recording will use — 0.0 (the default) if nothing's been
     * measured for that route, which reproduces the pre-calibration
     * pre-roll-only trim exactly.
     *
     * [backingTracks] (Phase 4 punch-in): if non-empty, mixed into the
     * output alongside the count-in/metronome click — real overdubbing,
     * hearing the existing song while recording a new take onto it.
     * [backingTracksStartFrame] is where, on the backing tracks' own
     * project timeline, this take's downbeat lands — once the take is
     * stopped and read back, its file frame 0 corresponds to exactly that
     * project frame, which is the `startFrame` to use when building the
     * `Clip` to [punchIn]. Leaving [backingTracks] empty (the default)
     * reproduces plain click-only recording exactly.
     */
    fun armRecording(
        filePath: String,
        bpm: Double,
        beatsPerBar: Int,
        countInBeats: Int,
        calibrationOffsetFrames: Double = 0.0,
        backingTracks: List<MultitrackTrackSpec> = emptyList(),
        backingTracksStartFrame: Long = 0L,
    ): Boolean {
        if (!ensureCreated()) return false
        val f = flattenTracks(backingTracks)
        return nativeArmRecording(
            handle, filePath, bpm, beatsPerBar, countInBeats, calibrationOffsetFrames, f.clipBuffers,
            f.clipStartFrames, f.clipBufferOffsetFrames, f.clipLengthFrames, f.trackClipCounts, f.trackGains,
            f.trackMuted, f.trackSoloed, backingTracksStartFrame,
        )
    }

    fun stopRecording() {
        if (handle != 0L) nativeStopRecording(handle)
    }

    fun startPlayback(filePath: String): Boolean = ensureCreated() && nativeStartPlayback(handle, filePath)

    /** Plays an in-memory buffer directly — see [Calibration.buildPreMixedVerificationBuffer] (Rule A). */
    fun startPlaybackFromBuffer(buffer: FloatArray): Boolean =
        ensureCreated() && nativeStartPlaybackFromBuffer(handle, buffer)

    fun stopPlayback() {
        if (handle != 0L) nativeStopPlayback(handle)
    }

    /**
     * Phase 4, second slice: real-time multitrack playback with N clips per
     * track — flattens `tracks` into track-major flat arrays plus a
     * per-track clip count, matching `nativeStartMultitrackPlayback`'s
     * marshaling in `jni_bridge.cpp`. Mixing happens chunk-at-a-time inside
     * the RT callback via `dsp::mixTracksInto`; this call itself just
     * marshals the buffers across JNI once and returns immediately (mirrors
     * [startPlaybackFromBuffer]'s synchronous, no-loader-thread shape).
     */
    fun startMultitrackPlayback(tracks: List<MultitrackTrackSpec>): Boolean {
        if (!ensureCreated()) return false
        val f = flattenTracks(tracks)
        return nativeStartMultitrackPlayback(
            handle, f.clipBuffers, f.clipStartFrames, f.clipBufferOffsetFrames, f.clipLengthFrames,
            f.trackClipCounts, f.trackGains, f.trackMuted, f.trackSoloed,
        )
    }

    /**
     * Splices [insertClip] into a single track's existing clip list — see
     * `dsp::punchIn` in `track_mixer.h` for the trim/split semantics
     * (clips the insert fully covers are dropped, clips it partially
     * overlaps are trimmed, a clip straddling both edges splits into a
     * head and a tail fragment). Stateless: doesn't touch the live engine
     * or [handle], so it's safe to call even if the engine was never
     * started. The actual splicing logic lives once, in C++
     * (`dsp::punchIn`), so this and any eventual JVM reference mixer stay
     * independent implementations of *mixing*, not of punch-in splicing.
     */
    fun punchIn(existingClips: List<MultitrackClipSpec>, insertClip: MultitrackClipSpec): List<MultitrackClipSpec> {
        val existingBuffers: Array<FloatArray> = Array(existingClips.size) { existingClips[it].buffer }
        val existingStart = LongArray(existingClips.size) { existingClips[it].startFrame }
        val existingOffset = LongArray(existingClips.size) { existingClips[it].bufferOffsetFrames }
        val existingLength = LongArray(existingClips.size) { existingClips[it].lengthFrames }

        // Worst case: EVERY existing clip straddles both edges of the
        // insert and splits into a head + tail fragment (2 outputs per
        // input clip), plus the insert clip itself (always exactly 1 more)
        // — see the C++-side doc comment on nativePunchIn. A single clip
        // splitting is 2 outputs from 1 input, not 1; sizing this as
        // existingClips.size + 1 undercounts and previously crashed with
        // an ArrayIndexOutOfBoundsException the first time a real
        // straddling punch-in ran on device.
        val outCapacity = existingClips.size * 2 + 1
        val outBuffers = arrayOfNulls<FloatArray>(outCapacity)
        val outStart = LongArray(outCapacity)
        val outOffset = LongArray(outCapacity)
        val outLength = LongArray(outCapacity)

        val resultCount = nativePunchIn(
            existingBuffers, existingStart, existingOffset, existingLength,
            insertClip.buffer, insertClip.startFrame, insertClip.bufferOffsetFrames, insertClip.lengthFrames,
            outBuffers, outStart, outOffset, outLength,
        )
        return (0 until resultCount).map { i ->
            MultitrackClipSpec(
                buffer = outBuffers[i]!!, startFrame = outStart[i], bufferOffsetFrames = outOffset[i],
                lengthFrames = outLength[i],
            )
        }
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
        calibrationOffsetFrames: Double,
        backingClipBuffers: Array<FloatArray>,
        backingClipStartFrames: LongArray,
        backingClipBufferOffsetFrames: LongArray,
        backingClipLengthFrames: LongArray,
        backingTrackClipCounts: IntArray,
        backingTrackGains: FloatArray,
        backingTrackMuted: BooleanArray,
        backingTrackSoloed: BooleanArray,
        backingTracksStartFrame: Long,
    ): Boolean
    private external fun nativeStopRecording(handle: Long)
    private external fun nativeStartPlayback(handle: Long, filePath: String): Boolean
    private external fun nativeStartPlaybackFromBuffer(handle: Long, buffer: FloatArray): Boolean
    private external fun nativeStopPlayback(handle: Long)
    private external fun nativeStartMultitrackPlayback(
        handle: Long,
        clipBuffers: Array<FloatArray>,
        clipStartFrames: LongArray,
        clipBufferOffsetFrames: LongArray,
        clipLengthFrames: LongArray,
        trackClipCounts: IntArray,
        trackGains: FloatArray,
        trackMuted: BooleanArray,
        trackSoloed: BooleanArray,
    ): Boolean
    private external fun nativePunchIn(
        existingClipBuffers: Array<FloatArray>,
        existingClipStartFrames: LongArray,
        existingClipBufferOffsetFrames: LongArray,
        existingClipLengthFrames: LongArray,
        insertClipBuffer: FloatArray,
        insertStartFrame: Long,
        insertBufferOffsetFrames: Long,
        insertLengthFrames: Long,
        outClipBuffers: Array<FloatArray?>,
        outClipStartFrames: LongArray,
        outClipBufferOffsetFrames: LongArray,
        outClipLengthFrames: LongArray,
    ): Int
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

/**
 * One clip within a [MultitrackTrackSpec] — mirrors `dsp::Clip` in
 * `track_mixer.h`. [bufferOffsetFrames]/[lengthFrames] let a clip be a
 * trimmed *view* into [buffer] rather than requiring the buffer itself to
 * be exactly the clip's length — e.g. a punch-in tail fragment, whose
 * `bufferOffsetFrames` advances past the head that got trimmed off but
 * whose underlying `buffer` is still the original full take.
 */
data class MultitrackClipSpec(
    val buffer: FloatArray,
    val startFrame: Long,
    val bufferOffsetFrames: Long = 0L,
    val lengthFrames: Long = buffer.size.toLong() - bufferOffsetFrames,
)

/**
 * One track's worth of input to [AudioEngine.startMultitrackPlayback].
 * Phase 4, second slice: [clips] is a list (not a single buffer) — what
 * makes a punched-in track (several clip fragments sharing one timeline)
 * actually playable in real time. See docs/handoff/PHASE-04.md.
 */
data class MultitrackTrackSpec(
    val clips: List<MultitrackClipSpec>,
    val gain: Float = 1.0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
)

/** JNI-shaped output of [AudioEngine.flattenTracks] — see its doc comment. */
private class FlattenedTracks(
    val clipBuffers: Array<FloatArray>,
    val clipStartFrames: LongArray,
    val clipBufferOffsetFrames: LongArray,
    val clipLengthFrames: LongArray,
    val trackClipCounts: IntArray,
    val trackGains: FloatArray,
    val trackMuted: BooleanArray,
    val trackSoloed: BooleanArray,
)
