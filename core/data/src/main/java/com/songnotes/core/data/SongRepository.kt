package com.songnotes.core.data

import android.content.Context
import com.songnotes.core.domain.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin facade over [SongDatabase]/[SongDao], the Room+SQLCipher replacement for
 * `:app`'s pre-Phase-6 `SongStorage.kt` (one JSON file per song). Not yet wired
 * into `:app` -- see docs/handoff/PHASE-06.md -- this exists so the encrypted
 * database can be built, tested, and verified on a real device as a standalone
 * piece before the live song list/editor screens are switched over to it.
 */
class SongRepository(context: Context) {
    private val db: SongDatabase = SongDatabase.open(context, KeystoreDbKeyProvider(context).getOrCreateDbKey())
    private val dao = db.songDao()

    fun observeAll(): Flow<List<Song>> = dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getById(id: String): Song? = dao.getById(id)?.toDomain()

    suspend fun upsert(song: Song) = dao.upsert(SongEntity.fromDomain(song))

    suspend fun delete(song: Song) = dao.delete(SongEntity.fromDomain(song))
}
