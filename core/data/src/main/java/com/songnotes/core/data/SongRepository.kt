package com.songnotes.core.data

import android.content.Context
import com.songnotes.core.domain.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A song as shown in the list, carrying the sync bookkeeping [Song] itself
 * doesn't (Phase 13) -- [pendingSync]/[isOnAccount] are exactly what
 * `SongListScreen`'s delete-confirmation copy and per-row sync indicator need,
 * and neither survives [SongEntity.toDomain]'s conversion to a plain [Song].
 * [isOnAccount] is `remoteRev != null`: this device has confirmed, at some
 * point, that this song exists on the server -- independent of whether sync
 * is *currently* enabled, since a song can be "on the account" even while the
 * user is signed out right now.
 */
data class SongListItem(val song: Song, val pendingSync: Boolean, val isOnAccount: Boolean)

/**
 * Thin facade over [SongDatabase]/[SongDao], the Room+SQLCipher replacement for
 * `:app`'s pre-Phase-6 `SongStorage.kt` (one JSON file per song) -- now wired
 * into `:app`'s live `SongListScreen`/`SongEditorScreen` (see
 * docs/handoff/PHASE-06.md's third pass).
 *
 * Every write bumps `rev` and sets `pendingSync = true` (read the existing row
 * first, same "read current rev before writing" reasoning as the web app's
 * `CloudSongsRepository.update`) regardless of whether an account is even
 * signed in -- `pendingSync` means "locally modified since the last confirmed
 * push," which is exactly as true for a song that will never be pushed as one
 * that will. [delete]/[deleteRespectingSync] tombstone instead of a real Room
 * delete when a song has ever reached the server (same "a delete is just
 * another row version, not a real DELETE" reasoning that fixes cross-device
 * delete propagation server-side, docs/handoff/PHASE-07.md) -- hard-deleting a
 * synced row here would give the sync engine nothing to push, defeating the
 * whole point of the tombstone scheme. A song that was **never** pushed
 * ([SongEntity.remoteRev] still null) has no such obligation and is hard-deleted
 * instead (Phase 13, [deleteRespectingSync]) -- otherwise every offline-only
 * user would accumulate permanent tombstones nothing will ever reclaim.
 *
 * Phase 13: local-first, opt-in manual sync. This class itself needs no
 * account and makes no network call either way -- what changed is that
 * `:app` no longer treats "signed in" as a precondition for using it at all
 * (see `docs/handoff/PHASE-13-local-first.md`).
 */
class SongRepository(private val dao: SongDao) {
    /** Convenience constructor for real call sites -- resolves the process-wide [SongDatabase] singleton (Phase 13; see its own doc comment for why a second `RoomDatabase` instance here was a real bug, not just a leak). Tests should call the primary constructor directly with a fake [SongDao]. */
    constructor(context: Context) : this(SongDatabase.getInstance(context).songDao())

    fun observeAll(): Flow<List<Song>> = dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    /** [observeAll] plus the sync bookkeeping the list screen's delete-confirmation copy and sync badge need (Phase 13). */
    fun observeAllWithSyncState(): Flow<List<SongListItem>> =
        dao.observeAllEntities().map { entities ->
            entities.map { SongListItem(song = it.toDomain(), pendingSync = it.pendingSync, isOnAccount = it.remoteRev != null) }
        }

    suspend fun getById(id: String): Song? = dao.getById(id)?.toDomain()

    /**
     * Phase 13 fix for three defects [SongEntity.fromDomain] alone can't avoid,
     * all present since Phase 7/8 and all made much worse under manual-only
     * sync (see `docs/handoff/PHASE-13-local-first.md`'s "Part 0"):
     *
     * - **`remoteRev`** -- [SongEntity.fromDomain] never sets it (it's not part
     *   of the domain [Song] at all), so without restoring it here every edit to
     *   an already-synced song reset it to `null`. [SyncEngine.pushPending] then
     *   read "never pushed" and attempted an `insert` on an id that already
     *   existed remotely -- a Postgres unique-violation that made
     *   `SongSyncWorker` fail (previously retry) forever on that song.
     * - **`deletedAt`** -- was previously hard-coded to `null` on every upsert,
     *   silently resurrecting a tombstoned song the instant it was touched by
     *   anything that calls `upsert` (e.g. the legacy-JSON import re-running).
     * - **`createdAt`** -- `SongEditorScreen.emptySong` used to hand this method
     *   a domain `Song` with `createdAt = 0L` for a song id it hadn't loaded yet,
     *   which would have been pushed to the server as a 1970 timestamp. Real
     *   creation time now only ever comes from the first-ever upsert of that id.
     */
    suspend fun upsert(song: Song) {
        val existing = dao.getByIdIncludingDeleted(song.id)
        val entity = SongEntity.fromDomain(song).copy(
            rev = (existing?.rev ?: 0) + 1,
            deletedAt = existing?.deletedAt,
            pendingSync = true,
            remoteRev = existing?.remoteRev,
            createdAt = existing?.createdAt ?: song.createdAt,
        )
        dao.upsert(entity)
    }

    /** Always tombstones, even for a song that was never pushed. Prefer [deleteRespectingSync] from the UI; kept for the sync engine's own use and existing callers. */
    suspend fun delete(song: Song) {
        val existing = dao.getByIdIncludingDeleted(song.id) ?: return
        dao.upsert(
            existing.copy(
                deletedAt = System.currentTimeMillis(),
                rev = existing.rev + 1,
                pendingSync = true,
            ),
        )
    }

    /**
     * Phase 13: the real delete path for `SongListScreen`. A song this device
     * has never confirmed reached the server ([SongEntity.remoteRev] still
     * null) has nothing for a future sync to push, so tombstoning it would just
     * accumulate a permanent, unreclaimable row for a local-only user who will
     * never sign in -- hard-delete instead. A song that *has* reached the
     * server still needs the tombstone so the next sync can propagate the
     * delete.
     */
    suspend fun deleteRespectingSync(song: Song) {
        val existing = dao.getByIdIncludingDeleted(song.id) ?: return
        if (existing.remoteRev == null) {
            dao.deleteById(song.id)
        } else {
            dao.upsert(
                existing.copy(
                    deletedAt = System.currentTimeMillis(),
                    rev = existing.rev + 1,
                    pendingSync = true,
                ),
            )
        }
    }
}
