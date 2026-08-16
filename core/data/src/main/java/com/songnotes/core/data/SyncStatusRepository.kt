package com.songnotes.core.data

import android.content.Context
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * Bumped whenever sign-in/sign-out/DEK-unlock changes state outside Compose's
 * normal recomposition triggers -- `SupabaseAuthRepository.isSignedIn` and
 * `KeySession.isUnlocked()` are plain reads of mutable state Compose can't
 * observe directly. Same idiom `MainActivity`'s own `authVersion`/
 * `keySessionVersion` locals used, independently, twice over before Phase 13
 * centralized it here so [SyncStatusRepository] can react to it too, not just
 * the two screens that invented it.
 */
object AuthStateSignal {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    fun bump() { _version.value++ }
}

/**
 * Everything a Sync press needs to know before it can proceed.
 * `SyncController.gate` is the only thing that should branch on this to
 * decide where to route the user -- UI code should read [SyncStatus] to
 * DISPLAY state, not to make gating decisions itself.
 */
sealed interface SyncGate {
    data object Ready : SyncGate
    data object NeedsSignIn : SyncGate
    data object NeedsUnlock : SyncGate
    data object NotConfigured : SyncGate
}

/**
 * Everything the Phase 13 sync banner / header needs, combined into one
 * observable snapshot. [enabled] is the real "does sync apply to this device
 * right now" signal -- true only when the device has opted in AND that
 * opt-in still points at the currently signed-in account (see
 * `SyncController.enableSyncFor`'s account-switch handling).
 */
data class SyncStatus(
    val enabled: Boolean,
    val signedIn: Boolean,
    val unlocked: Boolean,
    val unsyncedSongs: Int,
    val unsyncedDeletes: Int,
    /** null means "never synced." */
    val lastSyncAtMs: Long?,
    val lastError: String?,
    val running: Boolean,
) {
    val unsyncedTotal: Int get() = unsyncedSongs + unsyncedDeletes

    companion object {
        val EMPTY = SyncStatus(
            enabled = false, signedIn = false, unlocked = false,
            unsyncedSongs = 0, unsyncedDeletes = 0, lastSyncAtMs = null, lastError = null, running = false,
        )
    }
}

/**
 * Combines the Room pending-count [Flow]s, [SyncPreferences.observe], live
 * [WorkInfo] for [SongSyncWorker.UNIQUE_WORK_NAME], and [AuthStateSignal] into
 * one reactive [SyncStatus] -- what `SyncBanner`/`SyncHeader` collect. Requires
 * [SongDatabase.getInstance] (Phase 13's DB singleton, see its own doc
 * comment) -- a `Flow` from a second `RoomDatabase` instance over the same
 * file would never see writes made through a different instance, which would
 * make this banner silently go stale the moment any *other* screen wrote a song.
 */
class SyncStatusRepository(
    private val dao: SongDao,
    private val prefs: SyncPreferences,
    private val workManager: WorkManager,
    private val authRepo: SupabaseAuthRepository,
) {
    constructor(context: Context) : this(
        dao = SongDatabase.getInstance(context).songDao(),
        prefs = SyncPreferences(context),
        workManager = WorkManager.getInstance(context),
        authRepo = SupabaseAuthRepository(),
    )

    fun observe(): Flow<SyncStatus> = combine(
        dao.observePendingSongCount(),
        dao.observePendingDeleteCount(),
        prefs.observe(),
        workManager.getWorkInfosForUniqueWorkFlow(SongSyncWorker.UNIQUE_WORK_NAME),
        AuthStateSignal.version,
    ) { pendingSongs, pendingDeletes, settings, workInfos, _ ->
        SyncStatus(
            enabled = settings.syncEnabled && settings.syncAccountUserId == authRepo.currentUserId,
            signedIn = authRepo.isSignedIn,
            unlocked = KeySession.isUnlocked(),
            unsyncedSongs = pendingSongs,
            unsyncedDeletes = pendingDeletes,
            lastSyncAtMs = settings.lastSyncAtMs.takeIf { it > 0L },
            lastError = settings.lastSyncError,
            running = workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED },
        )
    }
}

/**
 * The single place that decides whether a Sync press can proceed, so neither
 * `SyncHeader` nor `SyncBanner` needs to re-derive gating logic. Real network
 * calls only happen via [requestSync] -> [SongSyncWorker.enqueueOneTime]; the
 * rest of this class is device-local bookkeeping and reads.
 */
class SyncController(
    private val context: Context,
    private val prefs: SyncPreferences = SyncPreferences(context),
    private val authRepo: SupabaseAuthRepository = SupabaseAuthRepository(),
) {
    /** What the UI should do before a Sync press can actually run -- check this first and route to sign-in/unlock instead of calling [requestSync] directly when it isn't [SyncGate.Ready]. */
    fun gate(): SyncGate = when {
        !SupabaseClientProvider.isConfigured -> SyncGate.NotConfigured
        !authRepo.isSignedIn -> SyncGate.NeedsSignIn
        !KeySession.isUnlocked() -> SyncGate.NeedsUnlock
        else -> SyncGate.Ready
    }

    /**
     * Enqueues a manual sync pass, adopting on the first sync for this account
     * (see [SyncPreferences.adoptionCompletedForUserId]). Callers should check
     * [gate] first.
     *
     * The `Log.i` here is deliberate, not incidental: this is the ONLY
     * legitimate place a sync should ever be enqueued from (see
     * `docs/handoff/PHASE-13-local-first.md`'s "Debugging an unexplained
     * sync"). If `SongSyncWorker.doWork()` ever runs without this line
     * immediately preceding it in Logcat, that's a real, reproducible signal
     * that something enqueued a sync outside a direct user action -- a live
     * investigation during this phase found exactly one such occurrence and
     * could not pin down the trigger from evidence available after the fact
     * (three separate clean, isolated repro attempts -- cold restart, package
     * reinstall with a valid session, and an explicit sign-out/sign-in cycle
     * -- each produced zero unexplained syncs). This log line is what would
     * make the *next* occurrence traceable instead of another dead end.
     */
    fun requestSync() {
        val userId = authRepo.currentUserId ?: return
        val adopt = prefs.adoptionCompletedForUserId != userId
        Log.i("SyncController", "requestSync: enqueuing sync (adopt=$adopt)")
        SongSyncWorker.enqueueOneTime(context, adopt = adopt)
    }

    /**
     * Call right after a sign-in/sign-up the user chose to enable sync for.
     * Detects an account switch -- this device's songs were last synced (or
     * never synced) against a DIFFERENT account -- and detaches every local
     * `remoteRev` so the next sync re-adopts cleanly instead of producing a
     * conflict copy for every single song (every `remoteRev` would otherwise
     * point at ids the new account's RLS policy can't see at all).
     */
    suspend fun enableSyncFor(userId: String) {
        val previousAccount = prefs.syncAccountUserId
        if (previousAccount != null && previousAccount != userId) {
            SongDatabase.getInstance(context).songDao().detachFromRemote()
            prefs.adoptionCompletedForUserId = null
        }
        prefs.syncEnabled = true
        prefs.syncAccountUserId = userId
        AuthStateSignal.bump()
    }

    /** Disables sync. Touches only [SyncPreferences]; see its `disableSync` doc comment for why no `songs` row is ever touched here. */
    fun disableSync() {
        prefs.disableSync()
        AuthStateSignal.bump()
    }
}
