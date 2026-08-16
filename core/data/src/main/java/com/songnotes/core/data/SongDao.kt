package com.songnotes.core.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    /** Excludes tombstoned rows (`deletedAt IS NOT NULL`) -- kept in the table for sync, never shown. */
    @Query("SELECT * FROM songs WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SongEntity>>

    /** Same rows as [observeAll], as raw entities -- Phase 13's [SongRepository.observeAllWithSyncState] needs `pendingSync`/`remoteRev`, which [Song] doesn't carry. */
    @Query("SELECT * FROM songs WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAllEntities(): Flow<List<SongEntity>>

    /** Unfiltered by `deletedAt` -- the sync engine needs to see tombstoned rows too, unlike the UI. */
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE pendingSync = 1")
    suspend fun getPendingSync(): List<SongEntity>

    /** Every row regardless of `deletedAt` -- Phase 13's adoption pass (`SyncEngine.reconcileForAdoption`) needs to see tombstones too. */
    @Query("SELECT * FROM songs")
    suspend fun getAllIncludingDeleted(): List<SongEntity>

    /** Live count for the Phase 13 sync banner -- non-deleted rows with a local edit not yet pushed. */
    @Query("SELECT COUNT(*) FROM songs WHERE pendingSync = 1 AND deletedAt IS NULL")
    fun observePendingSongCount(): Flow<Int>

    /** Live count for the Phase 13 sync banner -- tombstones not yet pushed. */
    @Query("SELECT COUNT(*) FROM songs WHERE pendingSync = 1 AND deletedAt IS NOT NULL")
    fun observePendingDeleteCount(): Flow<Int>

    @Upsert
    suspend fun upsert(song: SongEntity)

    /** Real hard delete -- only for the sync engine to reclaim a tombstone once it's confirmed pushed, never the UI. */
    @Delete
    suspend fun delete(song: SongEntity)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Detaches every row from its remote lineage (Phase 13: signing into a
     * *different* account than the one sync was last enabled for). Every local
     * `remoteRev` points at the previous account's row ids, which the new
     * account can't see under RLS -- pushing without this first would produce a
     * conflict copy for every single song. Forces a full re-adoption pass instead.
     */
    @Query("UPDATE songs SET remoteRev = NULL, pendingSync = 1")
    suspend fun detachFromRemote()
}
