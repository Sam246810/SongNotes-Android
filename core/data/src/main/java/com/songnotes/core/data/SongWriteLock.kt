package com.songnotes.core.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes every read-modify-write of a song row in this process: the
 * editor's saves ([SongRepository]) and sync's local writes ([SyncEngine]).
 *
 * Without it, a sync that read a row, pushed it, and then wrote "confirmed,
 * clean" back would overwrite an edit the editor saved while the push was out
 * -- marking that edit synced although it was never sent (a real lost update,
 * docs/SYNC-MERGE.md). Each holder re-reads the row inside the lock and checks
 * nothing changed underneath. Never held across a network call.
 */
object SongWriteLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
