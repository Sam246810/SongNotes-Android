package com.songnotes.core.audio

import android.content.Context

/**
 * Persists a measured calibration offset per [AudioRoute.routeKey] — the
 * plan's "per-route calibration storage," swapped whenever the active
 * route changes rather than a single global offset. `SharedPreferences`
 * rather than a real database: this is a handful of small key-value pairs
 * (one offset + one timestamp per route ever calibrated), and no broader
 * data layer exists yet to justify introducing Room for just this — see
 * docs/handoff/PHASE-00.md's "don't front-load empty modules" note, same
 * reasoning applied to storage as to Gradle modules.
 */
class CalibrationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class StoredCalibration(val offsetFrames: Double, val measuredAtEpochMs: Long)

    fun save(routeKey: String, offsetFrames: Double) {
        prefs.edit()
            .putFloat(offsetKey(routeKey), offsetFrames.toFloat())
            .putLong(timestampKey(routeKey), System.currentTimeMillis())
            .apply()
    }

    fun load(routeKey: String): StoredCalibration? {
        if (!prefs.contains(offsetKey(routeKey))) return null
        return StoredCalibration(
            offsetFrames = prefs.getFloat(offsetKey(routeKey), 0f).toDouble(),
            measuredAtEpochMs = prefs.getLong(timestampKey(routeKey), 0L),
        )
    }

    fun clear(routeKey: String) {
        prefs.edit().remove(offsetKey(routeKey)).remove(timestampKey(routeKey)).apply()
    }

    private fun offsetKey(routeKey: String) = "offset_frames_$routeKey"
    private fun timestampKey(routeKey: String) = "measured_at_$routeKey"

    private companion object {
        const val PREFS_NAME = "songnotes_calibration_routes"
    }
}
