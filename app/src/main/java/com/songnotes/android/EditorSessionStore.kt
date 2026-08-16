package com.songnotes.android

import android.content.Context

/**
 * Remembers which song the editor was last open on, so a cold start (process
 * death while the app was foregrounded, or Android reclaiming memory while
 * backgrounded) can reopen it directly instead of landing on the song list --
 * Phase 13's "pick up where you left off." Plain SharedPreferences, one key,
 * same idiom `:core:audio`'s `CalibrationStore`/`RecordingInputPreference`
 * already use in this repo -- this one lives in `:app` rather than
 * `:core:data` since it's UI navigation state, not song/sync data.
 */
class EditorSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("songnotes_editor_session", Context.MODE_PRIVATE)

    var lastOpenSongId: String?
        get() = prefs.getString(KEY_LAST_OPEN_SONG_ID, null)
        set(value) { prefs.edit().putString(KEY_LAST_OPEN_SONG_ID, value).apply() }

    private companion object {
        const val KEY_LAST_OPEN_SONG_ID = "last_open_song_id"
    }
}
