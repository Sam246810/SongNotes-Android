package com.songnotes.core.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * The small slice of SharedPreferences [SyncPreferences] actually needs,
 * split out so it's unit-testable against an in-memory fake without
 * Robolectric (this repo has none -- `core/data/build.gradle.kts`'s
 * `unitTests.isReturnDefaultValues = true` would otherwise make a real
 * `Context.getSharedPreferences(...)` call in a JVM test silently return
 * nulls/defaults instead of failing loudly, hiding a real bug behind a green
 * checkmark).
 */
interface KeyValueStore {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)

    /** [listener] fires after any write through any [KeyValueStore] instance backed by the same underlying store. Returns an unregister function. */
    fun addListener(listener: () -> Unit): () -> Unit
}

private class SharedPreferencesStore(context: Context, name: String) : KeyValueStore {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun getString(key: String) = prefs.getString(key, null)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }

    override fun addListener(listener: () -> Unit): () -> Unit {
        val realListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> listener() }
        prefs.registerOnSharedPreferenceChangeListener(realListener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(realListener) }
    }
}

/** Snapshot of every [SyncPreferences] value at once -- what [SyncPreferences.observe] emits. */
data class SyncSettings(
    val syncEnabled: Boolean,
    val explainerAcknowledged: Boolean,
    val syncAccountUserId: String?,
    val adoptionCompletedForUserId: String?,
    val lastSyncAtMs: Long,
    val lastSyncError: String?,
    val legacyJsonImportDone: Boolean,
)

/**
 * Device-local sync bookkeeping (Phase 13) -- deliberately SharedPreferences,
 * not DataStore: `CalibrationStore`/`RecordingInputPreference` (`:core:audio`)
 * already establish the idiom in this repo, DataStore isn't a dependency
 * anywhere in `gradle/libs.versions.toml`, and the one thing DataStore would
 * buy here (reactive [Flow]s) is the ~10 lines [observe] already does over
 * `OnSharedPreferenceChangeListener`.
 *
 * None of this is secret and none of it is the account DEK or password --
 * just "did this device opt into sync, for which account, and when did it
 * last succeed." Losing it (e.g. app data cleared) is recoverable: sync just
 * re-runs its adoption pass once re-enabled, same as a fresh install.
 */
class SyncPreferences(private val store: KeyValueStore) {
    constructor(context: Context) : this(SharedPreferencesStore(context, PREFS_NAME))

    /**
     * The opt-in latch. Only ever set `true` right after a sign-in/sign-up
     * that actually produced a live session -- see `SyncController.enableSyncFor`.
     * A sign-up that returns `null` (the email-confirmation path,
     * `SupabaseAuthRepository.signUp`) must never flip this, or the user ends
     * up with sync "on" and no account behind it.
     */
    var syncEnabled: Boolean
        get() = store.getBoolean(KEY_SYNC_ENABLED, false)
        set(value) = store.putBoolean(KEY_SYNC_ENABLED, value)

    /** Whether the sync opt-in explainer (what syncing means, the "lose your password AND recovery code = unrecoverable" warning) has been shown and acknowledged. */
    var explainerAcknowledged: Boolean
        get() = store.getBoolean(KEY_EXPLAINER_ACK, false)
        set(value) = store.putBoolean(KEY_EXPLAINER_ACK, value)

    /** Which account [syncEnabled] belongs to. Compared against the currently signed-in user to detect an account switch -- see `SyncController.enableSyncFor`. */
    var syncAccountUserId: String?
        get() = store.getString(KEY_SYNC_ACCOUNT_USER_ID)
        set(value) = store.putString(KEY_SYNC_ACCOUNT_USER_ID, value)

    /** Non-null once `SyncEngine.reconcileForAdoption` has completed a full clean pass for this account -- `SyncController.requestSync` uses this to decide whether the next sync needs `adopt = true`. */
    var adoptionCompletedForUserId: String?
        get() = store.getString(KEY_ADOPTION_DONE_FOR)
        set(value) = store.putString(KEY_ADOPTION_DONE_FOR, value)

    /** 0L means "never synced." */
    var lastSyncAtMs: Long
        get() = store.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) = store.putLong(KEY_LAST_SYNC_AT, value)

    /** Non-null after a failed sync; cleared on the next success. What the banner's error state reads. */
    var lastSyncError: String?
        get() = store.getString(KEY_LAST_SYNC_ERROR)
        set(value) = store.putString(KEY_LAST_SYNC_ERROR, value)

    /** Run-once flag for the pre-Phase-6 legacy-JSON-song import -- without it, `SongListScreen` re-imported (and re-dirtied/resurrected) every legacy song on every visit to the list. */
    var legacyJsonImportDone: Boolean
        get() = store.getBoolean(KEY_LEGACY_IMPORT_DONE, false)
        set(value) = store.putBoolean(KEY_LEGACY_IMPORT_DONE, value)

    fun snapshot(): SyncSettings = SyncSettings(
        syncEnabled = syncEnabled,
        explainerAcknowledged = explainerAcknowledged,
        syncAccountUserId = syncAccountUserId,
        adoptionCompletedForUserId = adoptionCompletedForUserId,
        lastSyncAtMs = lastSyncAtMs,
        lastSyncError = lastSyncError,
        legacyJsonImportDone = legacyJsonImportDone,
    )

    /** Emits the current [snapshot] immediately, then again after any write through this store (from anywhere -- e.g. `SongSyncWorker` writing `lastSyncAtMs` on a background thread). Feeds `SyncStatusRepository.observe`. */
    fun observe(): Flow<SyncSettings> = callbackFlow {
        trySend(snapshot())
        val unregister = store.addListener { trySend(snapshot()) }
        awaitClose { unregister() }
    }.conflate()

    /**
     * Sign-out / disabling sync. Touches ONLY this device-local bookkeeping --
     * never a row in `songs`, and specifically never `remoteRev` -- so signing
     * back into the SAME account later resumes without a fresh adoption pass.
     * [legacyJsonImportDone] is deliberately untouched: it tracks a one-time
     * local migration with no relationship to any account.
     *
     * [syncAccountUserId] is ALSO deliberately left untouched -- a real, live
     * bug found during Phase 13's own testing, not a hypothetical: it's the
     * one field `SyncController.enableSyncFor` reads to detect an account
     * switch (`previousAccount != null && previousAccount != userId`) and
     * detach every local `remoteRev` before the next sync. Every local row's
     * `remoteRev` keeps pointing at whichever account it was last actually
     * pushed to regardless of any sign-out in between (`songs` is never
     * touched by sign-out, see above) -- so the one fact this class needs to
     * track is exactly "which account do these `remoteRev`s belong to," and
     * that doesn't become false just because the user signed out. Clearing it
     * here made every sign-in look like a first-ever sign-in to
     * `enableSyncFor`, silently skipping the detach: a song already pushed to
     * account A would sit there marked "synced" (`pendingSync = false`) after
     * signing into account B forever, having never actually reached B's
     * `songs` table at all -- caught live when a real push to a fresh test
     * account silently didn't happen for a song that looked perfectly synced
     * in the UI. [adoptionCompletedForUserId] still clears normally; at worst
     * that costs one redundant (idempotent, zero-network-write-if-nothing-
     * changed) adoption pass on the next sign-in to the SAME account, a fine
     * trade for correctness on the switch case.
     */
    fun disableSync() {
        syncEnabled = false
        adoptionCompletedForUserId = null
        lastSyncAtMs = 0L
        lastSyncError = null
    }

    companion object {
        private const val PREFS_NAME = "songnotes_sync"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_EXPLAINER_ACK = "explainer_acknowledged"
        private const val KEY_SYNC_ACCOUNT_USER_ID = "sync_account_user_id"
        private const val KEY_ADOPTION_DONE_FOR = "adoption_completed_for_user_id"
        private const val KEY_LAST_SYNC_AT = "last_sync_at_ms"
        private const val KEY_LAST_SYNC_ERROR = "last_sync_error"
        private const val KEY_LEGACY_IMPORT_DONE = "legacy_json_import_done"
    }
}
