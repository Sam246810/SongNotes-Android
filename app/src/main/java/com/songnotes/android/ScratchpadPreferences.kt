package com.songnotes.android

import android.content.Context

/** Which Scratchpad layout a tour or the screen is about. */
enum class ScratchpadMode { SIMPLE, ADVANCED }

/**
 * App-wide (not per-song) Scratchpad preferences. SharedPreferences, matching
 * [ThemePreference] and `EditorSessionStore`.
 */
class ScratchpadPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("songnotes_scratchpad", Context.MODE_PRIVATE)

    /** Simple (voice-notes style) unless the user switched to Advanced; remembered across songs. */
    var mode: ScratchpadMode
        get() = if (prefs.getString(KEY_MODE, null) == ScratchpadMode.ADVANCED.name) ScratchpadMode.ADVANCED else ScratchpadMode.SIMPLE
        set(value) { prefs.edit().putString(KEY_MODE, value.name).apply() }

    /** Set once a mode's tour has been finished or skipped, on any song. */
    fun tutorialSeen(mode: ScratchpadMode): Boolean = prefs.getBoolean(seenKey(mode), false)

    fun setTutorialSeen(mode: ScratchpadMode) { prefs.edit().putBoolean(seenKey(mode), true).apply() }

    /** Settings → a mode's tour queues it here; the next Scratchpad open in that mode plays it and clears it. */
    var requestedTutorial: ScratchpadMode?
        get() = prefs.getString(KEY_TUTORIAL_REQUESTED, null)?.let { runCatching { ScratchpadMode.valueOf(it) }.getOrNull() }
        set(value) { prefs.edit().putString(KEY_TUTORIAL_REQUESTED, value?.name).apply() }

    /** Whether the user has been asked for notification permission (asked once, on the first Record). */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIF_ASKED, value).apply() }

    private fun seenKey(mode: ScratchpadMode) = "tutorial_seen_${mode.name.lowercase()}"

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_TUTORIAL_REQUESTED = "tutorial_requested_mode"
        const val KEY_NOTIF_ASKED = "notification_permission_asked"
    }
}
