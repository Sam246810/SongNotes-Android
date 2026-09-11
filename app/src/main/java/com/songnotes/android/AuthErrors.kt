package com.songnotes.android

import android.util.Log

/**
 * The single place an unexpected auth/crypto failure becomes text the user
 * sees. Never surfaces `e.message`.
 *
 * This is not a precaution -- it's the fix for a defect already found live on
 * the sync path and never propagated here. `SongSyncWorker.doWork`'s generic
 * catch documents it: Postgrest/Ktor exceptions carry the full failed request,
 * including the `Authorization` header, so rendering `e.message` put a bearer
 * JWT on screen in the sync banner during real testing. That worker was fixed
 * to show a fixed string and log the real exception; [AuthScreen],
 * [LockedAccountScreen] and [RecoveryUnlockScreen] -- the three screens that
 * actually handle credentials -- kept doing `errorText = e.message` until now.
 *
 * The real exception still reaches Logcat under [TAG] in full, so
 * `adb logcat -s SongNotesAuth:*` remains the way to diagnose a genuine
 * failure. Typed exceptions we author ourselves ([com.songnotes.core.data.EnvelopeKeyMismatchException],
 * [com.songnotes.core.data.RecoveryCodeMismatchException]) deliberately do NOT
 * go through here -- their messages are written by us, carry no request state,
 * and are the useful thing to show.
 */
private const val TAG = "SongNotesAuth"

fun reportAuthFailure(context: String, e: Throwable): String {
    Log.e(TAG, "$context failed", e)
    return "$context didn't work. Check your connection and try again."
}
