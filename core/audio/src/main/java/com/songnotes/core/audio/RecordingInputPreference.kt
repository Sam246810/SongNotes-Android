package com.songnotes.core.audio

import android.content.Context

/**
 * Persists one durable choice: should recording force the phone's own
 * built-in mic even when a device with its own mic (a wired headset,
 * Bluetooth) is connected? A user who plugs in a headset specifically to
 * hear the metronome click without it bleeding into the take, but whose
 * headset also happens to have a mic, needs this override — otherwise
 * Android's default routing sends input to the headset mic right along
 * with output, which is exactly the opposite of what they wanted.
 *
 * `SharedPreferences` for one boolean — same "small scoped storage class,
 * no front-loaded data layer" precedent as [CalibrationStore], and
 * deliberately NOT part of [MultitrackProjectStorage]'s project manifest:
 * this is a per-device recording preference, not project data, so it
 * should keep applying regardless of which project is open.
 */
class RecordingInputPreference(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var forceBuiltinMic: Boolean
        get() = prefs.getBoolean(KEY_FORCE_BUILTIN_MIC, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_BUILTIN_MIC, value).apply()

    private companion object {
        const val PREFS_NAME = "songnotes_recording_input"
        const val KEY_FORCE_BUILTIN_MIC = "force_builtin_mic"
    }
}
