package com.songnotes.core.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.jan.supabase.auth.auth

/**
 * Thin `CoroutineWorker` wrapper around [SyncEngine] -- WorkManager glue only,
 * no sync logic of its own, so [SyncEngine] stays testable without a real
 * background job. WorkManager here is just the mechanism for running suspend
 * code reliably off the UI thread with real `Constraints`
 * (`NetworkType.CONNECTED`) -- Phase 13 made sync strictly manual, so
 * **nothing ever schedules this worker itself**. [enqueueOneTime] only ever
 * runs in direct response to the user's own Sync button press (see
 * `SyncController.requestSync`); there is deliberately no periodic
 * `WorkRequest` anywhere in this codebase.
 *
 * Requires [KeySession] to already have the DEK established in this process
 * (the user is signed in AND has unlocked this session -- there is currently
 * no way to unlock the DEK from a cold background job with no UI). Before
 * Phase 13 this case was a silent `Result.success()` no-op, tolerable because
 * `LockedAccountScreen` gated the whole Songs screen so the user would hit a
 * password prompt on next launch regardless. Phase 13 removed that gate
 * entirely (local songs are always usable, locked or not) -- a Sync press is
 * now the ONLY place a locked session becomes visible at all, so it must fail
 * loudly here and let the UI route to re-unlock, never no-op.
 */
class SongSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Every legitimate run of this worker is preceded by a matching
        // "SyncController: requestSync: enqueuing sync" log line, logged at
        // the moment of enqueue -- see that Log.i call's own doc comment for
        // why. If this line ever appears in Logcat WITHOUT one immediately
        // before it, that's the trace evidence needed to finally pin down
        // where an unexplained sync came from.
        Log.i(TAG, "doWork starting: workId=$id runAttemptCount=$runAttemptCount tags=$tags adoptInput=${inputData.getBoolean(KEY_ADOPT, false)}")

        val prefs = SyncPreferences(applicationContext)
        val adopt = inputData.getBoolean(KEY_ADOPT, false)

        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
            ?: return fail(prefs, "Not signed in.")
        val dek = KeySession.current()
            ?: return fail(prefs, "Enter your password to sync.")
        val sessionDekId = KeySession.currentDekId()

        return try {
            // If this session's DEK came from an envelope that's no longer the
            // account's active one (a recovery-code-lost reset on another
            // device rotated it -- see the web app's accountRecovery.js
            // rotateAndPurge), pushing local edits would re-encrypt them under
            // a key that's already dead, and pulling would just fail to
            // decrypt every row. Clear the stale session and fail loudly so
            // the UI routes to re-unlock with the CURRENT password -- Phase 13
            // replaces the previous silent `Result.success()` here, which
            // relied on the (now-removed) LockedAccountScreen gate to ever
            // surface this at all.
            if (sessionDekId != null) {
                val currentDekId = SupabaseAuthRepository().fetchCurrentDekId()
                if (currentDekId != null && currentDekId != sessionDekId) {
                    Log.w(TAG, "DEK rotated elsewhere (dekId mismatch) -- clearing stale session, failing sync")
                    KeySession.clear()
                    return fail(prefs, "Your account's encryption key changed elsewhere. Enter your current password to sync.")
                }
            }

            val db = SongDatabase.getInstance(applicationContext)
            val outcome = SyncEngine(db.songDao()).sync(userId, dek, sessionDekId, adopt = adopt)
            if (adopt) prefs.adoptionCompletedForUserId = userId
            prefs.lastSyncAtMs = System.currentTimeMillis()
            prefs.lastSyncError = null
            Log.i(TAG, "sync ok: pushed=${outcome.pushed} pulled=${outcome.pulled} conflicts=${outcome.conflictCopies} reIded=${outcome.reIded}")
            Result.success()
        } catch (e: java.io.IOException) {
            // Network-layer failure (no connection, DNS, timeout) -- safe to
            // be a little more specific than the generic branch below.
            Log.e(TAG, "sync failed (network)", e)
            fail(prefs, "Couldn't reach the network. Check your connection and try again.")
        } catch (e: Exception) {
            // Deliberately never surface e.message in the UI -- Postgrest/Ktor
            // exceptions carry the full request, including the auth header.
            // Found via live testing (not this suite): a real failure here
            // -- a genuine schema mismatch, "Could not find the 'dek_id'
            // column of 'songs' in the schema cache" -- put a bearer JWT on
            // screen in the sync banner, since the raw exception message was
            // shown directly before this fix. The real exception (full
            // message, stack trace) still goes to Logcat under tag
            // "SongSyncWorker" via this Log.e call -- `adb logcat -s
            // SongSyncWorker:*` is where to look when diagnosing a real sync
            // failure; the UI/banner only ever shows the safe, generic
            // string passed to `fail()` below.
            Log.e(TAG, "sync failed", e)
            fail(prefs, "Sync failed. Try again.")
        }
    }

    /**
     * Phase 13: never [Result.retry]. Under strictly-manual sync, WorkManager's
     * own exponential-backoff retry -- re-attempting later on its own network
     * callbacks, with no user action -- IS an automatic sync, exactly what
     * "sync only happens when the user presses the button" rules out. Every
     * failure is instead written to [SyncPreferences.lastSyncError] (what the
     * list-screen banner reads) and simply waits for the user's next Sync press.
     */
    private fun fail(prefs: SyncPreferences, message: String): Result {
        prefs.lastSyncError = message
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    companion object {
        private const val TAG = "SongSyncWorker"

        /** Not private -- `SyncStatusRepository` reads live `WorkInfo` for this name to drive the banner's "syncing…" state. */
        const val UNIQUE_WORK_NAME = "song_sync"
        private const val KEY_ADOPT = "adopt"
        private const val KEY_ERROR = "error"

        /**
         * Enqueues a single sync pass. Every real call site is a direct user
         * action (see `SyncController.requestSync`) -- Phase 13 removed the
         * two call sites that used to fire this automatically on sign-in/
         * sign-up. [ExistingWorkPolicy.KEEP], not the previous `REPLACE`: a
         * double-tap on Sync must not cancel a batch that's already half
         * pushed, which would leave some rows clean and others dirty with
         * mismatched `remoteRev`s.
         *
         * @param adopt true only for the first sync after enabling sync (or
         * switching accounts) -- see `SyncController.requestSync`, which is
         * the one real caller that decides this; folds every existing local
         * song onto the account via `SyncEngine.reconcileForAdoption` before
         * the ordinary push/pull.
         */
        fun enqueueOneTime(context: Context, adopt: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<SongSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ADOPT to adopt))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
