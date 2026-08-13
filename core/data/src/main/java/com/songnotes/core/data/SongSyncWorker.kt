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
import io.github.jan.supabase.auth.auth

/**
 * Thin `CoroutineWorker` wrapper around [SyncEngine] -- WorkManager glue only,
 * no sync logic of its own, so [SyncEngine] stays testable without a real
 * background job.
 *
 * Requires [KeySession] to already have the DEK established in this process
 * (i.e. the user is actively signed in this session) -- there is currently no
 * way to unlock the DEK from a cold background job with no UI (that would need
 * the account-DEK Keystore device wrap from Phase 6 wired into a
 * background-safe unlock flow, which doesn't exist yet). If the DEK isn't
 * available, this no-ops successfully rather than retrying forever: there's
 * nothing productive to do until the user is signed in again, and that will
 * naturally trigger its own sync (see `enqueueOneTime`'s call site in
 * `:app`'s sign-in screen).
 */
class SongSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id ?: return Result.success()
        val dek = KeySession.current() ?: return Result.success()
        val sessionDekId = KeySession.currentDekId()

        return try {
            // If this session's DEK came from an envelope that's no longer the
            // account's active one (a recovery-code-lost reset on another
            // device rotated it -- see the web app's accountRecovery.js
            // rotateAndPurge), pushing local edits would re-encrypt them under
            // a key that's already dead, and pulling would just fail to
            // decrypt every row. Neither is useful; skip the whole sync and
            // drop the stale session so the next foreground use re-prompts for
            // the current password (LockedAccountScreen) instead of silently
            // failing forever in the background.
            if (sessionDekId != null) {
                val currentDekId = SupabaseAuthRepository().fetchCurrentDekId()
                if (currentDekId != null && currentDekId != sessionDekId) {
                    Log.w("SongSyncWorker", "DEK rotated elsewhere (dekId mismatch) -- clearing stale session, skipping sync")
                    KeySession.clear()
                    return Result.success()
                }
            }

            val db = SongDatabase.open(applicationContext, KeystoreDbKeyProvider(applicationContext).getOrCreateDbKey())
            SyncEngine(db.songDao()).sync(userId, dek, sessionDekId)
            Result.success()
        } catch (e: Exception) {
            Log.e("SongSyncWorker", "sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "song_sync"

        /** Enqueues a single sync pass now (e.g. right after sign-in, or after a local edit). Coalesces with any already-pending request. */
        fun enqueueOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<SongSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
