package com.songnotes.core.data

import kotlinx.coroutines.flow.flow

/**
 * In-memory [SongDao] shared by every JVM test in this package (Phase 13: split
 * out of `SyncEngineTest.kt`, where it originated, so `SongRepositoryTest` and
 * `SyncEngineAdoptionTest` don't each need their own copy) -- same "fake, not a
 * real Room DB" precedent as the JS side's FakeRemoteAdapter.
 */
class FakeSongDao : SongDao {
    val rows = LinkedHashMap<String, SongEntity>()

    override fun observeAll() = flow { emit(rows.values.filter { it.deletedAt == null }.sortedByDescending { it.updatedAt }) }
    override fun observeAllEntities() = flow { emit(rows.values.filter { it.deletedAt == null }.sortedByDescending { it.updatedAt }) }

    override suspend fun getByIdIncludingDeleted(id: String) = rows[id]
    override suspend fun getById(id: String) = rows[id]?.takeIf { it.deletedAt == null }
    override suspend fun getPendingSync() = rows.values.filter { it.pendingSync }
    override suspend fun getAllIncludingDeleted() = rows.values.toList()
    override fun observePendingSongCount() = flow { emit(rows.values.count { it.pendingSync && it.deletedAt == null }) }
    override fun observePendingDeleteCount() = flow { emit(rows.values.count { it.pendingSync && it.deletedAt != null }) }
    override suspend fun upsert(song: SongEntity) { rows[song.id] = song }
    override suspend fun delete(song: SongEntity) { rows.remove(song.id) }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun detachFromRemote() {
        for ((id, row) in rows) rows[id] = row.copy(remoteRev = null, pendingSync = true)
    }
}

/** In-memory [SongsRemoteAdapter] mirroring songsRepository.test.js's FakeRemoteAdapter's `WHERE id=? AND rev=?` semantics. */
class FakeSongsAdapter : SongsRemoteAdapter {
    val rows = LinkedHashMap<String, SongRow>()
    var insertCalls = 0
    var updateCalls = 0

    override suspend fun list(userId: String) = rows.values.filter { it.user_id == userId }
    override suspend fun getById(id: String) = rows[id]
    override suspend fun insert(row: SongRow): SongRow {
        insertCalls++
        if (rows.containsKey(row.id)) error("duplicate key value violates unique constraint (23505): ${row.id}")
        rows[row.id] = row
        return row
    }

    override suspend fun updateWithRevCheck(id: String, row: SongRow, expectedRev: Int): UpdateResult {
        updateCalls++
        val current = rows[id]
        if (current == null || current.rev != expectedRev) return UpdateResult.Conflict
        rows[id] = row
        return UpdateResult.Success(row)
    }
}
