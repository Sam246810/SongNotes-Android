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

    /** Unfiltered by `deletedAt` -- the sync engine needs to see tombstoned rows too, unlike the UI. */
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :id AND deletedAt IS NULL")
    suspend fun getById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE pendingSync = 1")
    suspend fun getPendingSync(): List<SongEntity>

    @Upsert
    suspend fun upsert(song: SongEntity)

    /** Real hard delete -- only for the sync engine to reclaim a tombstone once it's confirmed pushed, never the UI. */
    @Delete
    suspend fun delete(song: SongEntity)

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: String)
}
