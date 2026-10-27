package com.songnotes.core.data

import com.songnotes.core.domain.Song
import com.songnotes.core.domain.merge.contentEquals
import com.songnotes.core.domain.merge.mergeSongs
import java.util.UUID
import org.json.JSONObject

/**
 * What a [SyncEngine.sync] call actually did, for the caller to show the user -- Phase 13's manual-only model means a bare "it succeeded" spinner isn't enough.
 * [merged] counts songs edited on another device too, whose edits were merged with this one's (docs/SYNC-MERGE.md).
 * [restored] counts songs deleted here that came back because another device had edited them.
 * [unreadable] counts remote songs this pass couldn't decrypt or parse -- they're left out, not fatal, but no longer silently.
 */
data class SyncOutcome(
    val pushed: Int,
    val pulled: Int,
    val merged: Int,
    val reIded: Int,
    val unreadable: Int = 0,
    val restored: Int = 0,
)

/** Internal accumulator for [SyncEngine.pushPending]'s per-row loop, folded into [SyncOutcome] by [SyncEngine.sync]. */
private data class PushResult(
    val pushed: Int = 0,
    val reIded: Int = 0,
    val merged: Int = 0,
    val restored: Int = 0,
    val unreadable: Int = 0,
    val firstError: Throwable? = null,
) {
    operator fun plus(o: PushResult) = PushResult(
        pushed + o.pushed, reIded + o.reIded, merged + o.merged, restored + o.restored, unreadable + o.unreadable,
        firstError ?: o.firstError,
    )
}

/** Internal accumulator for [SyncEngine.pull]. */
private data class PullResult(val pulled: Int, val unreadable: Int)

/**
 * The outbox push + pull -- the Kotlin twin of the desktop web app's
 * `CloudSongsRepository` (`songsRepository.js`): same rev-based optimistic
 * concurrency, same tombstones, and since the sync merge (docs/SYNC-MERGE.md)
 * the same answer to a lost race: merge, never a conflict copy. Deals in plain
 * suspend functions independent of WorkManager so it's testable without a real
 * background job -- `SongSyncWorker` is a thin `CoroutineWorker` wrapper around
 * [sync].
 *
 * A row with an unsent change ([SongEntity.pendingSync]) pushes with
 * "`WHERE rev = remoteRev`" -- the server version its change started from,
 * whose content [SongEntity.baseDocJson] holds. When that loses (another device
 * pushed first), the server's row is fetched and merged with this device's
 * version against that base, and the merge is pushed instead.
 *
 * Every local write here holds [SongWriteLock] and re-reads the row first: the
 * editor may have saved on top while a push was out, and that edit must stay
 * pending (or be merged in), never be marked synced unsent.
 *
 * Song content JSON is written and read by `SongDocument.kt`
 * ([songToDocument]/[songFromDocument]), the twin of the web app's
 * `src/store/songDocument.js`, both checked against `spec/song-documents.json`.
 */
class SyncEngine(
    private val dao: SongDao,
    private val adapter: SongsRemoteAdapter = SupabaseSongsAdapter(),
    /**
     * Called after a song is given a new id (the two re-id paths below). A
     * song's Scratchpad demo lives on disk under its id, outside Room, so
     * the caller must move it along -- otherwise the demo is orphaned and
     * the Songs list's orphan sweep used to delete it.
     */
    private val onSongReIded: (oldId: String, newId: String) -> Unit = { _, _ -> },
) {
    /**
     * @param dekId the envelope `dekId` [dek] came from (see [KeySession]) --
     * optional and defaulting to null purely so existing call sites (this
     * class's own tests included) don't all need updating at once; a null
     * `dekId` just means every pushed row's `dek_id` column comes out null too
     * (same as any pre-Phase-12 row). [SongSyncWorker] is the one real caller
     * that supplies it, having already confirmed it against the account's live
     * envelope before calling this at all.
     * @param adopt Phase 13: true only on the first sync after a device enables
     * sync (or switches accounts). Runs [reconcileForAdoption] before pushing,
     * so every local song this device already has gets folded onto the account
     * without duplicating or overwriting anything already there.
     * @return a [SyncOutcome] the caller can show the user, and can throw the
     * first push failure encountered (after still completing the pull, on the
     * theory that "download what I can" beats "nothing happened").
     */
    suspend fun sync(userId: String, dek: ByteArray, dekId: String? = null, adopt: Boolean = false): SyncOutcome {
        if (adopt) reconcileForAdoption(dek)

        // A first pass can leave freshly-pending rows behind that it never itself
        // pushes: a "remote row is simply gone" conflict re-queues the row as an
        // insert rather than retrying inline. One bounded extra pass -- not a loop
        // to a fixpoint -- catches it in the same sync() call.
        val push = pushPending(userId, dek, dekId) + pushPending(userId, dek, dekId)
        val pull = pull(userId, dek)

        push.firstError?.let { throw it }
        return SyncOutcome(
            pushed = push.pushed,
            pulled = pull.pulled,
            merged = push.merged,
            reIded = push.reIded,
            unreadable = pull.unreadable + push.unreadable,
            restored = push.restored,
        )
    }

    /**
     * Folds every local song onto the account the first time this device
     * enables sync (or re-enables it for a different account -- see
     * `SyncController.enableSyncFor`). A local song whose id already exists on
     * the account is the same song (same account, same id) and is merged with
     * the account's version without a base -- everything either side has is
     * kept. Only an account row this device can't read gets a new local id
     * instead, so both survive. See `docs/handoff/PHASE-13-local-first.md`.
     */
    private suspend fun reconcileForAdoption(dek: ByteArray) {
        for (entity in dao.getAllIncludingDeleted()) {
            if (entity.deletedAt != null && entity.remoteRev == null) {
                // Tombstoned locally, never pushed: nothing on the account for
                // this delete to propagate to. Reclaim it.
                dao.deleteById(entity.id)
                continue
            }
            if (entity.remoteRev != null) continue // already has a proven remote lineage

            // A null result is NOT proof the id is free -- RLS can hide a
            // different account's row with the same id from this query. That's
            // fine: pushPending's own insert-failure fallback resolves it.
            val remote = adapter.getById(entity.id) ?: continue
            val remoteSong = try {
                decryptSong(dek, remote)
            } catch (e: Exception) {
                null // undecryptable (different dek_id, corrupt) -- never merge with it
            }

            SongWriteLock.withLock {
                val cur = dao.getByIdIncludingDeleted(entity.id) ?: return@withLock
                if (remoteSong == null || remote.deleted_at != null) {
                    // Never touch a row this device can't read (or that the
                    // account deleted): give the local song a new identity, so
                    // pushPending inserts it as a song of its own.
                    val newId = UUID.randomUUID().toString()
                    dao.deleteById(cur.id)
                    dao.upsert(cur.copy(id = newId, rev = 1, remoteRev = null, pendingSync = true, baseDocJson = null))
                    onSongReIded(cur.id, newId)
                    return@withLock
                }
                val local = cur.toDomain()
                val merged = if (contentEquals(local, remoteSong)) remoteSong else mergeSongs(null, local, remoteSong)
                val pending = !contentEquals(merged, remoteSong)
                dao.upsert(
                    SongEntity.fromDomain(merged).copy(
                        rev = cur.rev + 1,
                        remoteRev = remote.rev,
                        pendingSync = pending,
                        baseDocJson = if (pending) songToDocument(remoteSong).toString() else null,
                        createdAt = merged.createdAt,
                    ),
                )
            }
        }
    }

    private suspend fun pushPending(userId: String, dek: ByteArray, dekId: String?): PushResult {
        var result = PushResult()
        for (entity in dao.getPendingSync()) {
            result += try {
                if (entity.remoteRev == null) pushInsert(entity, userId, dek, dekId) else pushUpdate(entity, userId, dek, dekId)
            } catch (e: Exception) {
                // One bad row must not block every other pending row from
                // pushing. Surface the first failure to sync()'s caller once
                // every row has had its turn.
                PushResult(firstError = e)
            }
        }
        return result
    }

    private suspend fun pushInsert(entity: SongEntity, userId: String, dek: ByteArray, dekId: String?): PushResult {
        return try {
            val inserted = adapter.insert(buildRow(entity, rev = 1, userId, dek, dekId))
            commitPushed(entity, inserted.rev)
            PushResult(pushed = 1)
        } catch (insertFailure: Exception) {
            // Always re-id and retry ONCE on any insert failure -- a fresh random
            // UUID essentially never collides again. The real case: a
            // cross-account primary-key collision RLS hides from getById
            // (confirmed live; see docs/handoff/PHASE-13-local-first.md).
            val reIdEntity = SongWriteLock.withLock {
                val cur = dao.getByIdIncludingDeleted(entity.id) ?: entity
                val moved = cur.copy(id = UUID.randomUUID().toString(), rev = 1, remoteRev = null, pendingSync = true, baseDocJson = null)
                dao.deleteById(entity.id)
                dao.upsert(moved)
                onSongReIded(entity.id, moved.id)
                moved
            }
            val inserted = adapter.insert(buildRow(reIdEntity, rev = 1, userId, dek, dekId))
            commitPushed(reIdEntity, inserted.rev)
            PushResult(pushed = 1, reIded = 1)
        }
    }

    /**
     * Pushes a change to a song the server already has. A lost race is resolved
     * per docs/SYNC-MERGE.md and retried, at most [MAX_PUSH_ATTEMPTS] times; a
     * song that keeps losing stays pending for the next sync.
     *
     *   this device   server     result
     *   edit          edited     merge both, push the merge
     *   edit          deleted    the edit wins: push the song back
     *   delete        unchanged  retry the delete
     *   delete        edited     the delete gives way: the song is restored
     *   delete        deleted    nothing to do
     */
    private suspend fun pushUpdate(entity: SongEntity, userId: String, dek: ByteArray, dekId: String?): PushResult {
        var current = entity
        var result = PushResult()
        repeat(MAX_PUSH_ATTEMPTS) {
            val expected = current.remoteRev ?: return result
            when (val sent = adapter.updateWithRevCheck(current.id, buildRow(current, rev = expected + 1, userId, dek, dekId), expected)) {
                is UpdateResult.Success -> {
                    commitPushed(current, sent.row.rev)
                    return result + PushResult(pushed = 1)
                }
                is UpdateResult.Conflict -> {
                    val remote = adapter.getById(current.id)
                    if (remote == null) {
                        // The remote row is genuinely gone (e.g. the web app's
                        // rotateAndPurge during a DEK rotation) -- not a race.
                        // Re-queue it as an insert.
                        SongWriteLock.withLock {
                            dao.getByIdIncludingDeleted(current.id)?.let { dao.upsert(it.copy(pendingSync = true, remoteRev = null, baseDocJson = null)) }
                        }
                        return result
                    }
                    val (next, outcome) = resolveLostRace(current, remote, dek)
                    result += outcome
                    current = next ?: return result
                }
            }
        }
        return result
    }

    /**
     * After a lost race against [remote]: the next row to push, or null when
     * nothing more needs pushing, plus what happened.
     */
    private suspend fun resolveLostRace(local: SongEntity, remote: SongRow, dek: ByteArray): Pair<SongEntity?, PushResult> {
        val remoteSong = try {
            if (remote.deleted_at == null) decryptSong(dek, remote) else null
        } catch (e: Exception) {
            // Never merge with, or push over, a row this device can't read
            // (most likely encrypted under another key). Stays pending.
            return null to PushResult(unreadable = 1)
        }
        val base = local.baseDocJson?.let { songFromDocument(local.id, JSONObject(it), toIso(local.createdAt), toIso(local.updatedAt)) }

        return SongWriteLock.withLock {
            val cur = dao.getByIdIncludingDeleted(local.id) ?: return@withLock null to PushResult()
            val remoteBase = remoteSong?.let { songToDocument(it).toString() }

            if (cur.deletedAt != null) {
                when {
                    remote.deleted_at != null -> {
                        dao.upsert(cur.copy(rev = cur.rev + 1, remoteRev = remote.rev, pendingSync = false, baseDocJson = null))
                        null to PushResult()
                    }
                    base != null && contentEquals(remoteSong, base) -> {
                        // Only the rev moved: delete what this device saw.
                        val retry = cur.copy(remoteRev = remote.rev, baseDocJson = remoteBase)
                        dao.upsert(retry)
                        retry to PushResult()
                    }
                    else -> {
                        // An edit elsewhere beats this delete: the song comes back.
                        dao.upsert(cleanFrom(remoteSong!!, cur, remote.rev))
                        null to PushResult(restored = 1)
                    }
                }
            } else {
                val localSong = local.toDomain()
                val merged = if (remoteSong == null) localSong else mergeSongs(base, localSong, remoteSong)
                // The editor may have saved on top while the push was out: fold that
                // edit in the same way, so it survives too.
                val final = if (cur.rev != local.rev) mergeSongs(localSong, cur.toDomain(), merged) else merged
                if (remoteSong != null && contentEquals(final, remoteSong)) {
                    dao.upsert(cleanFrom(remoteSong, cur, remote.rev))
                    null to PushResult(merged = 1)
                } else {
                    val next = SongEntity.fromDomain(final).copy(
                        rev = cur.rev + 1,
                        deletedAt = null, // an edit beats a delete made elsewhere
                        pendingSync = true,
                        remoteRev = remote.rev,
                        createdAt = cur.createdAt,
                        baseDocJson = remoteBase,
                    )
                    dao.upsert(next)
                    next to PushResult(merged = 1)
                }
            }
        }
    }

    /** [song] as a clean row confirmed at [remoteRev], replacing [cur]. */
    private fun cleanFrom(song: Song, cur: SongEntity, remoteRev: Int): SongEntity =
        SongEntity.fromDomain(song).copy(rev = cur.rev + 1, deletedAt = null, pendingSync = false, remoteRev = remoteRev, baseDocJson = null)

    /**
     * The push of [pushed] was confirmed at [newRemoteRev]. The row is clean now
     * -- unless the editor saved on top while the push was out (its local `rev`
     * moved), which stays pending with the pushed version as its new base.
     */
    private suspend fun commitPushed(pushed: SongEntity, newRemoteRev: Int) = SongWriteLock.withLock {
        val cur = dao.getByIdIncludingDeleted(pushed.id) ?: return@withLock
        if (cur.rev == pushed.rev) {
            dao.upsert(cur.copy(pendingSync = false, remoteRev = newRemoteRev, baseDocJson = null))
        } else {
            val newBase = if (pushed.deletedAt == null) songToDocument(pushed.toDomain()).toString() else null
            dao.upsert(cur.copy(pendingSync = true, remoteRev = newRemoteRev, baseDocJson = newBase))
        }
    }

    private suspend fun pull(userId: String, dek: ByteArray): PullResult {
        var pulled = 0
        var unreadable = 0
        for (row in adapter.list(userId)) {
            val local = dao.getByIdIncludingDeleted(row.id)
            // A song with an unsent change is never overwritten here -- its push
            // merges the server's version. Otherwise take anything newer than the
            // last version this device confirmed.
            if (local != null && (local.pendingSync || row.rev <= (local.remoteRev ?: -1))) continue

            if (row.deleted_at != null) {
                if (local == null) continue
                val applied = SongWriteLock.withLock {
                    val cur = dao.getByIdIncludingDeleted(row.id) ?: return@withLock false
                    if (cur.pendingSync) return@withLock false
                    dao.upsert(cur.copy(deletedAt = parseIso(row.deleted_at), rev = cur.rev + 1, remoteRev = row.rev, pendingSync = false, baseDocJson = null))
                    true
                }
                if (applied) pulled++
                continue
            }

            // A single undecryptable row -- most likely encrypted under a
            // different DEK than this session's, or genuinely corrupt -- must not
            // abort the whole pull. Left untouched locally, but counted.
            val song = try {
                decryptSong(dek, row)
            } catch (e: Exception) {
                unreadable++
                continue
            }
            val applied = SongWriteLock.withLock {
                val cur = dao.getByIdIncludingDeleted(row.id)
                if (cur != null && cur.pendingSync) return@withLock false
                dao.upsert(SongEntity.fromDomain(song).copy(rev = (cur?.rev ?: 0) + 1, deletedAt = null, pendingSync = false, remoteRev = row.rev, baseDocJson = null))
                true
            }
            if (applied) pulled++
        }
        return PullResult(pulled, unreadable)
    }

    private fun decryptSong(dek: ByteArray, row: SongRow): Song {
        val contentJson = JSONObject(decryptContentJson(dek, row.content.toContentEnvelope()))
        return songFromDocument(row.id, contentJson, row.created_at, row.updated_at)
    }

    private fun buildRow(entity: SongEntity, rev: Int, userId: String, dek: ByteArray, dekId: String?): SongRow {
        val envelope = encryptContentJson(dek, songToDocument(entity.toDomain()).toString())
        return SongRow(
            id = entity.id,
            user_id = userId,
            encrypted = true,
            content = envelope.toJsonElement(),
            is_locked = false,
            dek_id = dekId,
            rev = rev,
            deleted_at = entity.deletedAt?.let(::toIso),
            created_at = toIso(entity.createdAt),
            updated_at = toIso(entity.updatedAt),
        )
    }

    companion object {
        /** At most this many lost races in a row per song per sync before leaving it for the next one. */
        const val MAX_PUSH_ATTEMPTS = 3
    }
}
