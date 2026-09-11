package com.songnotes.core.data

import android.content.Context

/**
 * Records that local song storage had to be discarded because the Android
 * Keystore key protecting it was lost (see
 * [KeystoreDbKeyProvider.getOrCreateDbKey] and [DbKeyResult.RecreatedAfterKeyLoss]).
 *
 * The app recovers automatically -- it resets and keeps working rather than
 * crash-looping -- but recovering silently would be its own failure. A
 * local-first app is expected to hold songs that were never pushed to any
 * account, and for those the reset is genuine, unrecoverable data loss. The
 * user is owed a plain statement that it happened, and the one actionable next
 * step: songs that *had* been synced come back with a Sync press.
 *
 * SharedPreferences, matching [SyncPreferences] and `:core:audio`'s
 * `CalibrationStore`. Deliberately a separate store from [SyncPreferences]:
 * this is not sync bookkeeping, it survives sign-out, and
 * [SyncPreferences.disableSync] must never clear it.
 *
 * The flag is written on the very launch that performs the reset, and cleared
 * only when the user acknowledges it, so it survives the process being killed
 * before they ever saw the notice.
 */
class LocalDataResetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True from the moment a Keystore-loss reset happens until [acknowledge] is called. */
    val wasReset: Boolean
        get() = prefs.getBoolean(KEY_WAS_RESET, false)

    fun markReset() {
        // commit(), not apply(): this is written during database construction on
        // a path that has just discarded the user's local songs. An async write
        // that loses the race with the process dying would mean the reset
        // happened and the user is never told -- the one outcome this class
        // exists to prevent.
        prefs.edit().putBoolean(KEY_WAS_RESET, true).commit()
    }

    fun acknowledge() {
        prefs.edit().putBoolean(KEY_WAS_RESET, false).apply()
    }

    private companion object {
        const val PREFS_NAME = "songnotes_local_data_reset"
        const val KEY_WAS_RESET = "was_reset_after_keystore_loss"
    }
}
