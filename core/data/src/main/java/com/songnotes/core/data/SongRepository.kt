package com.songnotes.core.data

import android.content.Context
import com.songnotes.core.domain.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin facade over [SongDatabase]/[SongDao], the Room+SQLCipher replacement for
 * `:app`'s pre-Phase-6 `SongStorage.kt` (one JSON file per song) -- now wired
 * into `:app`'s live `SongListScreen`/`SongEditorScreen` (see
 * docs/handoff/PHASE-06.md's third pass).
 *
 * Phase 7: every write bumps `rev` and sets `pendingSync = true` (read the
 * existing row first, same "read current rev before writing" reasoning as the
 * web app's `CloudSongsRepository.update`), and [delete] tombstones instead of
 * a real Room delete -- same "a delete is just another row version, not a real
 * DELETE" reasoning that fixes cross-device delete propagation server-side
 * (docs/handoff/PHASE-07.md), now applied locally too: hard-deleting the row
 * here would give the future `SyncWorker` nothing to push, defeating the whole
 * point of the tombstone scheme.
 */
class SongRepository(context: Context) {
    private val db: SongDatabase = SongDatabase.open(context, KeystoreDbKeyProvider(context).getOrCreateDbKey())
    private val dao = db.songDao()

    fun observeAll(): Flow<List<Song>> = dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getById(id: String): Song? = dao.getById(id)?.toDomain()

    suspend fun upsert(song: Song) {
        val existing = dao.getByIdIncludingDeleted(song.id)
        val entity = SongEntity.fromDomain(song).copy(
            rev = (existing?.rev ?: 0) + 1,
            deletedAt = null,
            pendingSync = true,
        )
        dao.upsert(entity)
    }

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
}
