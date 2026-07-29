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
) {
    companion object {
        const val OFFSET_IS_RECORDING = 0
        const val OFFSET_IS_PLAYING = 4
        const val OFFSET_FRAMES_RECORDED = 8
        const val OFFSET_PLAYBACK_FRAME = 12
        const val OFFSET_PLAYBACK_TOTAL_FRAMES = 16
        const val OFFSET_XRUN_COUNT = 20
        const val OFFSET_FRAMES_DROPPED = 24
        const val SIZE_BYTES = 28

        fun idle() = EngineState(
            isRecording = false,
            isPlaying = false,
            framesRecorded = 0,
            playbackFrame = 0,
            playbackTotalFrames = 0,
            xRunCount = 0,
            framesDropped = 0,
        )
    }
}
