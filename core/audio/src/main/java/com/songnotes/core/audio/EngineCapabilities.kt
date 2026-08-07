package com.songnotes.core.audio

/**
 * What the audio engine actually negotiated with the device, as opposed to
 * what was requested. `isMMapUsed` is the single honest answer to "did I get
 * the fast path" — AAudio can silently hand back a mixer-routed stream even
 * when the fast path was requested.
 */
data class EngineCapabilities(
    val audioApi: String,
    val sampleRate: Int,
    val framesPerBurst: Int,
    val channelCount: Int,
    val format: String,
    val sharingMode: String,
    val performanceMode: String,
    val isMMapUsed: Boolean,
    val xRunCount: Int,
    val lastError: String?,
    /**
     * The input stream's actual `AudioDeviceInfo.id` (0/oboe::kUnspecified
     * if no input stream is open) — the output-only readout above didn't
     * cover the input side at all until this; see
     * [AudioEngine.setPreferredInputDevice] for what can move it away from
     * the system default.
     */
    val inputDeviceId: Int = 0,
) {
    companion object {
        fun unavailable(reason: String? = null) = EngineCapabilities(
            audioApi = "-",
            sampleRate = 0,
            framesPerBurst = 0,
            channelCount = 0,
            format = "-",
            sharingMode = "-",
            performanceMode = "-",
            isMMapUsed = false,
            xRunCount = 0,
            lastError = reason,
            inputDeviceId = 0,
        )
    }
}
