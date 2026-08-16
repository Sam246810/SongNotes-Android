package com.songnotes.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A process-lifetime coroutine scope, deliberately NOT `rememberCoroutineScope()`
 * (Phase 13). [SongDraftAutosaver] launches its debounced write here rather
 * than on a composable's own scope so leaving the editor's composition --
 * back-press, navigating away, a config change -- can no longer cancel a
 * write that's still inside its debounce delay. That cancellation was the
 * root cause of the pre-Phase-13 "last few keystrokes silently lost on exit"
 * bug: `SongEditorScreen.persist()` used `rememberCoroutineScope()`, so its
 * `delay(400)` died with the composition instead of completing.
 *
 * `SupervisorJob` so one failed write (e.g. a `SQLiteException`) can't cancel
 * every other coroutine sharing this scope.
 */
object AppScope {
    val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
