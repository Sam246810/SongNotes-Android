package com.songnotes.android

import android.content.Context

/**
 * App-wide (not per-song) Scratchpad preferences. SharedPreferences, matching
 * [ThemePreference] and `EditorSessionStore`.
 */
class ScratchpadPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("songnotes_scratchpad", Context.MODE_PRIVATE)

    /** The Scratchpad opens in landscape unless the user switched it to portrait. */
    var portrait: Boolean
        get() = prefs.getBoolean(KEY_PORTRAIT, false)
        set(value) { prefs.edit().putBoolean(KEY_PORTRAIT, value).apply() }

    /** Set once the first-run tutorial has been finished or skipped, on any song. */
    var tutorialSeen: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_SEEN, false)
        set(value) { prefs.edit().putBoolean(KEY_TUTORIAL_SEEN, value).apply() }

    /** Settings → "Scratchpad tutorial" sets this; the next Scratchpad open shows the tutorial and clears it. */
    var tutorialRequested: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_REQUESTED, false)
        set(value) { prefs.edit().putBoolean(KEY_TUTORIAL_REQUESTED, value).apply() }

    /** Whether the user has been asked for notification permission (asked once, on the first Record). */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIF_ASKED, value).apply() }

    private companion object {
        const val KEY_PORTRAIT = "portrait"
        const val KEY_TUTORIAL_SEEN = "tutorial_seen"
        const val KEY_TUTORIAL_REQUESTED = "tutorial_requested"
        const val KEY_NOTIF_ASKED = "notification_permission_asked"
    }
}
