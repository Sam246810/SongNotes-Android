package com.songnotes.android

import com.songnotes.core.data.SongRepository
import com.songnotes.core.domain.Song
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Replaces `SongEditorScreen`'s old `persist()` (Phase 13) -- that function
 * was labelled a debounce but had neither: no `Job` handle, so it couldn't
 * cancel a still-pending write, meaning N keystrokes produced N independent
 * coroutines, N DB writes, and `rev` inflating by N instead of by 1 per
 * editing pause. It also ran on `rememberCoroutineScope()`, so leaving the
 * composition (back-press, navigating away) cancelled whatever write was
 * still inside its `delay(400)` -- the last few keystrokes before any exit
 * other than the Done button were silently lost.
 *
 * This fixes both: [schedule] cancels the previous pending write before
 * starting a new one (a real debounce, collapsing a typing burst into one
 * write), and it runs on [AppScope.io] -- a process-lifetime scope, not tied
 * to any composable -- so leaving the editor's composition no longer cancels
 * an in-flight write. [flush] (called from the editor's lifecycle `ON_STOP`
 * and `onDispose`, and from the Done button) forces the latest pending
 * content out immediately. The [Mutex] exists so a lifecycle-triggered
 * [flush] can never race the debounce timer's own write into two separate
 * `upsert` calls (which would double-bump `rev` for one logical edit).
 */
class SongDraftAutosaver(private val repo: SongRepository, private val scope: CoroutineScope) {
    private var job: Job? = null
    private val pending = AtomicReference<Song?>(null)
    private val writeLock = Mutex()

    /** Call on every content change. Cancels any prior pending write and schedules a new one [DEBOUNCE_MS] out -- a burst of edits collapses to a single DB write. */
    fun schedule(song: Song) {
        pending.set(song)
        job?.cancel()
        job = scope.launch {
            delay(DEBOUNCE_MS)
            flush()
        }
    }

    /** Writes whatever is currently pending, if anything, right now -- no debounce. Safe to call redundantly (a no-op if nothing is pending). */
    suspend fun flush() {
        writeLock.withLock {
            pending.getAndSet(null)?.let { repo.upsert(it) }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 350L
    }
}
