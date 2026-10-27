package com.songnotes.core.audio

/**
 * Mirrors `core/audio/src/main/cpp/engine_state_block.h` exactly — the byte
 * offsets below and the native struct's field order/size must be kept in
 * sync by hand, there is no generated binding for this.
 */
data class EngineState(
    val isRecording: Boolean,
    val isPlaying: Boolean,
    val framesRecorded: Int,
    val playbackFrame: Int,
    val playbackTotalFrames: Int,
    val xRunCount: Int,
    val framesDropped: Int,
    val isArmed: Boolean,
    val countInBeatsRemaining: Int,
    val isCalibrating: Boolean,
    val calibrationFramesCaptured: Int,
    /** Input peak while armed/recording, as |sample| x 1000 (1000 = full scale), with a ~300 ms fall. */
    val inputPeakMilli: Int,
    /** Latched once any sample of the current take hit full scale; cleared when the next take is armed. */
    val inputClipped: Boolean,
) {
    companion object {
        const val OFFSET_IS_RECORDING = 0
        const val OFFSET_IS_PLAYING = 4
        const val OFFSET_FRAMES_RECORDED = 8
        const val OFFSET_PLAYBACK_FRAME = 12
        const val OFFSET_PLAYBACK_TOTAL_FRAMES = 16
        const val OFFSET_XRUN_COUNT = 20
        const val OFFSET_FRAMES_DROPPED = 24
        const val OFFSET_IS_ARMED = 28
        const val OFFSET_COUNT_IN_BEATS_REMAINING = 32
        const val OFFSET_IS_CALIBRATING = 36
        const val OFFSET_CALIBRATION_FRAMES_CAPTURED = 40
        const val OFFSET_INPUT_PEAK_MILLI = 44
        const val OFFSET_INPUT_CLIPPED = 48
        const val SIZE_BYTES = 52

        fun idle() = EngineState(
            isRecording = false,
            isPlaying = false,
            framesRecorded = 0,
            playbackFrame = 0,
            playbackTotalFrames = 0,
            xRunCount = 0,
            framesDropped = 0,
            isArmed = false,
            countInBeatsRemaining = 0,
            isCalibrating = false,
            calibrationFramesCaptured = 0,
            inputPeakMilli = 0,
            inputClipped = false,
        )
    }
}
