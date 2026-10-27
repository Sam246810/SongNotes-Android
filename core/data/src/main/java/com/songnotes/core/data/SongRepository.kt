package com.songnotes.core.data

import android.content.Context
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.merge.contentEquals
import com.songnotes.core.domain.merge.mergeSongs
import com.songnotes.core.domain.merge.stampEdits
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
class SongRepository(
    private val dao: SongDao,
    /** Wall-clock source for edit stamps; injectable so tests control it. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
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
     * Song [id] as stored, re-emitted on every write, with whether it's been
     * deleted -- the editor watches it so a sync landing while the song is open
     * shows up in place. Emits null once the row is gone entirely.
     */
    fun observeStored(id: String): Flow<Pair<Song, Boolean>?> =
        dao.observeByIdIncludingDeleted(id).map { row -> row?.let { it.toDomain() to (it.deletedAt != null) } }

    /**
     * Whether Room knows this id at all, **tombstones included** -- unlike
     * [getById], which excludes them.
     *
     * Exists for the legacy-plaintext cleanup in `SongListScreen`, where the
     * question isn't "can the user open this song" but "has this song's content
     * definitely reached the encrypted database, such that the plaintext JSON
     * original is safe to delete". A tombstoned song answers yes to that: it
     * migrated successfully and was then deleted, so the plaintext copy is
     * unambiguously redundant -- and, being the lyrics of a song the user chose
     * to delete, is the copy that most wants removing.
     */
    suspend fun existsIncludingDeleted(id: String): Boolean = dao.getByIdIncludingDeleted(id) != null

    /**
     * True only when [id] is a song that was deleted (a tombstone row) -- not
     * when there's no row at all. Callers cleaning up data stored outside
     * Room (a song's Scratchpad demo) must treat "no row" as "unknown", never
     * as "deleted": sync can move a song to a new id, and an id with no row
     * may simply be one that hasn't been accounted for.
     */
    suspend fun isDeleted(id: String): Boolean = dao.getByIdIncludingDeleted(id)?.deletedAt != null

    /**
     * Saves [song] as is, with no merge -- for writers that aren't editing a
     * loaded version (the legacy-JSON import, new songs). The editor uses
     * [saveEdit]. Never resurrects a tombstoned song; see [write] for what's kept.
     */
    suspend fun upsert(song: Song) {
        SongWriteLock.withLock { write(song, dao.getByIdIncludingDeleted(song.id)) }
    }

    /**
     * The editor's save (docs/SYNC-MERGE.md): [song] is the editor's version, an
     * edit of [basedOn] -- the version it last loaded or saved. If the stored row
     * has moved on since (a sync landed while the song was open), the two are
     * merged instead of the editor's stale copy overwriting what sync brought in;
     * and a real edit of a song deleted elsewhere brings it back (an edit beats a
     * delete). Returns the song as stored, which the editor should show if it
     * differs from what it sent.
     */
    suspend fun saveEdit(song: Song, basedOn: Song): Song = SongWriteLock.withLock {
        val existing = dao.getByIdIncludingDeleted(song.id)
        val stored = existing?.toDomain()
        var next = song
        if (stored != null && existing.deletedAt == null && !contentEquals(stored, basedOn)) {
            next = mergeSongs(basedOn, song, stored)
        }
        val resurrect = existing?.deletedAt != null && !contentEquals(song, basedOn)
        write(next, existing, resurrect)
        dao.getByIdIncludingDeleted(song.id)!!.toDomain()
    }

    /**
     * Writes [song] over [existing] (the row as it is now; callers hold
     * [SongWriteLock]). Stamps what changed, bumps the local `rev`, marks the
     * row pending, and keeps what the domain [Song] doesn't carry:
     *
     * - **`remoteRev`** -- [SongEntity.fromDomain] never sets it, so without
     *   restoring it here every edit to an already-synced song reset it to
     *   `null`, and [SyncEngine.pushPending] tried to `insert` an id that already
     *   existed remotely (Phase 13, `docs/handoff/PHASE-13-local-first.md`).
     * - **`deletedAt`** -- kept, so anything that calls `upsert` (e.g. the
     *   legacy-JSON import re-running) never resurrects a tombstoned song; only
     *   [saveEdit] of a real edit does, deliberately.
     * - **`createdAt`** -- real creation time only ever comes from the first-ever
     *   upsert of that id, never a placeholder `0L`.
     * - **`baseDocJson`** -- snapshotted on the clean -> pending transition: the
     *   version the server confirmed, which a sync merge needs as its base.
     */
    private suspend fun write(song: Song, existing: SongEntity?, resurrect: Boolean = false) {
        val stamped = stampEdits(existing?.toDomain(), song, clock())
        val entity = SongEntity.fromDomain(stamped).copy(
            rev = (existing?.rev ?: 0) + 1,
            deletedAt = if (resurrect) null else existing?.deletedAt,
            pendingSync = true,
            remoteRev = existing?.remoteRev,
            createdAt = existing?.createdAt ?: song.createdAt,
            baseDocJson = baseFor(existing),
        )
        dao.upsert(entity)
    }

    /** Always tombstones, even for a song that was never pushed. Prefer [deleteRespectingSync] from the UI; kept for the sync engine's own use and existing callers. */
    suspend fun delete(song: Song) = SongWriteLock.withLock {
        val existing = dao.getByIdIncludingDeleted(song.id) ?: return@withLock
        dao.upsert(tombstone(existing))
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
    suspend fun deleteRespectingSync(song: Song) = SongWriteLock.withLock {
        val existing = dao.getByIdIncludingDeleted(song.id) ?: return@withLock
        if (existing.remoteRev == null) dao.deleteById(song.id) else dao.upsert(tombstone(existing))
    }

    private fun tombstone(existing: SongEntity): SongEntity = existing.copy(
        deletedAt = clock(),
        rev = existing.rev + 1,
        pendingSync = true,
        baseDocJson = baseFor(existing),
    )

    /**
     * The base a row's unsent change merges against: unchanged while it's already
     * pending, the row's current (server-confirmed) content when it was clean,
     * none when it's never reached the server.
     */
    private fun baseFor(existing: SongEntity?): String? = when {
        existing == null || existing.remoteRev == null -> null
        existing.pendingSync -> existing.baseDocJson
        else -> songToDocument(existing.toDomain()).toString()
    }
}
