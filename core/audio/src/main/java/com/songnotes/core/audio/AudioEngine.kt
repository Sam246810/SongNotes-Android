package com.songnotes.core.audio

/**
 * Kotlin facade over the native (Oboe-based) audio engine.
 *
 * Phase 0 scope only: open an output stream, play a 440 Hz test tone, report
 * what was actually obtained. There is deliberately no continuous state
 * channel yet — [capabilities] is a handful of cheap JNI calls, fine to poll
 * a couple of times a second. Once Phase 1+ needs a 60 Hz meter, that becomes
 * a direct-ByteBuffer state block instead, per the plan's threading design —
 * a real callback-rate audio thread must never make a JNI call per frame.
 *
 * Every `external fun` here is implemented in
 * `core/audio/src/main/cpp/jni_bridge.cpp`.
 */
class AudioEngine {

    private var handle: Long = 0L

    /** Starts (creating the native engine on first call) and plays the test tone. Returns whether the stream opened. */
    fun start(): Boolean {
        if (handle == 0L) {
            handle = nativeCreate()
        }
        if (handle == 0L) return false
        return nativeStartTestTone(handle)
    }

    fun stop() {
        if (handle != 0L) nativeStop(handle)
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

    /** Releases the native engine. Safe to call more than once; must be called from onDestroy. */
    fun release() {
        val h = handle
        if (h != 0L) {
            nativeDestroy(h)
            handle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeStartTestTone(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
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
