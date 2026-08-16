package com.songnotes.android

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.data.AuthStateSignal
import com.songnotes.core.data.SongRepository
import com.songnotes.core.data.SupabaseAuthRepository
import com.songnotes.core.data.SyncController
import com.songnotes.core.data.SyncGate
import com.songnotes.core.data.SyncStatus
import com.songnotes.core.data.SyncStatusRepository
import kotlinx.coroutines.launch

/**
 * Phase 13: `Screen.SongEditor` carries its own `songId` instead of a
 * sibling `editingSongId: String?` local that the old plain `enum` version
 * needed -- makes "in the editor with no id" a state this type can no longer
 * represent (the old code had a live `editingSongId!!` non-null assertion at
 * the `SongEditor` branch, unreachable in practice but fragile against any
 * navigation change).
 */
private sealed interface Screen {
    data object Songs : Screen
    data class SongEditor(val songId: String) : Screen
    data object Auth : Screen
    data object Unlock : Screen
    data object Scratchpad : Screen
    data object Piano : Screen
    data object Diagnostics : Screen
    data object Wizard : Screen
    data object Manual : Screen
    data object TapAlong : Screen
}

// FragmentActivity, not the usual bare ComponentActivity Compose apps default
// to -- BiometricPrompt (see DiagnosticsScreen.kt's device-wrap smoke test)
// needs one to host its internal dialog fragment. FragmentActivity extends
// ComponentActivity, so setContent {} and everything else here is unaffected.
class MainActivity : FragmentActivity() {

    private val audioEngine = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val authRepo = remember { SupabaseAuthRepository() }
            val syncController = remember { SyncController(context) }
            val statusRepo = remember { SyncStatusRepository(context) }
            val sessionStore = remember { EditorSessionStore(context) }
            val repo = remember { SongRepository(context) }

            // No navigation library wired up yet — just several screens, so a
            // plain sealed-interface toggle is the honest amount of
            // infrastructure for now (see docs/handoff/PHASE-00.md's
            // "don't front-load" note).
            //
            // Songs is the real home screen in every build, debug and
            // release alike (see docs/handoff/PHASE-11-prep-navigation.md).
            var screen by remember { mutableStateOf<Screen>(Screen.Songs) }

            // Phase 13: local-first -- reopen the song that was being edited
            // when the app last closed, rather than always landing on the
            // list. Guarded by getById (excludes tombstones): a song deleted
            // or pulled-as-deleted by a sync that happened while this device
            // wasn't looking must not resurrect a ghost editor.
            LaunchedEffect(Unit) {
                val lastOpenId = sessionStore.lastOpenSongId ?: return@LaunchedEffect
                if (repo.getById(lastOpenId) != null) {
                    screen = Screen.SongEditor(lastOpenId)
                } else {
                    sessionStore.lastOpenSongId = null
                }
            }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (val current = screen) {
                        Screen.Songs -> {
                            val status by statusRepo.observe().collectAsStateWithLifecycle(initialValue = SyncStatus.EMPTY)
                            var showExitDialog by remember { mutableStateOf(false) }

                            // The backstop for exits Android gives no reliable hook
                            // for (home button, recents swipe -- only Back is
                            // interceptable at all). SyncBanner below is the
                            // primary, always-visible signal; this only fires when
                            // there's actually something to warn about.
                            BackHandler(enabled = status.enabled && status.unsyncedTotal > 0) {
                                showExitDialog = true
                            }
                            if (showExitDialog) {
                                UnsyncedExitDialog(
                                    unsyncedCount = status.unsyncedTotal,
                                    lastSyncLabel = lastSyncLabel(status.lastSyncAtMs),
                                    onSyncNow = { syncController.requestSync(); showExitDialog = false },
                                    onLeaveAnyway = { showExitDialog = false; (context as? Activity)?.finish() },
                                    onDismiss = { showExitDialog = false },
                                )
                            }

                            // Shared by SyncHeader's own sign-in button and the
                            // SyncBanner rendered inside SongListScreen below --
                            // SyncController.gate() decides where a Sync press
                            // should route (see its own doc comment), and only
                            // MainActivity holds the `screen` navigation state
                            // needed to act on that decision.
                            val onSyncClick = {
                                when (syncController.gate()) {
                                    SyncGate.Ready -> syncController.requestSync()
                                    SyncGate.NeedsSignIn -> screen = Screen.Auth
                                    SyncGate.NeedsUnlock -> screen = Screen.Unlock
                                    // The banner only shows when sync is enabled,
                                    // which SyncController never sets true without
                                    // a configured client -- unreachable in practice.
                                    SyncGate.NotConfigured -> {}
                                }
                            }
                            val onSignInClick = { screen = Screen.Auth }

                            Column(modifier = Modifier.fillMaxSize()) {
                                SyncHeader(
                                    authRepo = authRepo,
                                    status = status,
                                    syncController = syncController,
                                    onSignInClick = onSignInClick,
                                )
                                // The only remaining way to reach Diagnostics (and
                                // transitively Wizard/Manual/TapAlong) in a real
                                // build. BuildConfig.DEBUG is a compile-time
                                // constant, so once R8 is on, it treats a release
                                // build's `if` as always-false and strips this
                                // branch -- and everything only it can reach --
                                // out of the release APK for free.
                                if (BuildConfig.DEBUG) {
                                    Button(
                                        onClick = { screen = Screen.Diagnostics },
                                        modifier = Modifier.padding(horizontal = 24.dp),
                                    ) {
                                        Text("Diagnostics")
                                    }
                                }
                                SongListScreen(
                                    status = status,
                                    onSyncClick = onSyncClick,
                                    onSignInClick = onSignInClick,
                                    onOpenSong = { id -> screen = Screen.SongEditor(id) },
                                    onOpenScratchpad = { screen = Screen.Scratchpad },
                                    onOpenPiano = { screen = Screen.Piano },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        is Screen.SongEditor -> SongEditorScreen(
                            songId = current.songId,
                            onDone = { screen = Screen.Songs },
                        )
                        Screen.Auth -> {
                            BackHandler { screen = Screen.Songs }
                            AuthScreen(onDone = { screen = Screen.Songs })
                        }
                        Screen.Unlock -> {
                            BackHandler { screen = Screen.Songs }
                            LockedAccountScreen(
                                onUnlocked = {
                                    AuthStateSignal.bump()
                                    syncController.requestSync()
                                    screen = Screen.Songs
                                },
                                onSignOut = {
                                    // LockedAccountScreen's own escape hatch
                                    // already called authRepo.signOut() (which
                                    // clears KeySession) before invoking this --
                                    // disableSync() is the Phase 13 bookkeeping
                                    // half of the same action, and bumps
                                    // AuthStateSignal itself.
                                    syncController.disableSync()
                                    screen = Screen.Songs
                                },
                            )
                        }
                        Screen.Wizard -> {
                            BackHandler { screen = Screen.Diagnostics }
                            CalibrationWizardScreen(engine = audioEngine, onDone = { screen = Screen.Diagnostics })
                        }
                        Screen.Manual -> {
                            BackHandler { screen = Screen.Diagnostics }
                            ManualCalibrationScreen(engine = audioEngine, onDone = { screen = Screen.Diagnostics })
                        }
                        Screen.TapAlong -> {
                            BackHandler { screen = Screen.Diagnostics }
                            TapAlongCalibrationScreen(engine = audioEngine, onDone = { screen = Screen.Diagnostics })
                        }
                        Screen.Scratchpad -> {
                            BackHandler { screen = Screen.Songs }
                            ScratchpadScreen(engine = audioEngine, onDone = { screen = Screen.Songs })
                        }
                        Screen.Piano -> {
                            BackHandler { screen = Screen.Songs }
                            PianoScreen(engine = audioEngine, onDone = { screen = Screen.Songs })
                        }
                        // Debug-only: reachable exclusively via the
                        // BuildConfig.DEBUG-gated button above, never from a
                        // release build.
                        Screen.Diagnostics -> {
                            BackHandler { screen = Screen.Songs }
                            Column(modifier = Modifier.fillMaxSize()) {
                                Button(
                                    onClick = { screen = Screen.Songs },
                                    modifier = Modifier.padding(24.dp),
                                ) {
                                    Text("Done")
                                }
                                Button(
                                    onClick = { screen = Screen.Wizard },
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                ) {
                                    Text("Open calibration wizard")
                                }
                                Button(
                                    onClick = { screen = Screen.Manual },
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                ) {
                                    Text("Open manual calibration")
                                }
                                Button(
                                    onClick = { screen = Screen.TapAlong },
                                    modifier = Modifier.padding(horizontal = 24.dp),
                                ) {
                                    Text("Open tap-along calibration")
                                }
                                DiagnosticsScreen(engine = audioEngine)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        // Deliberately does NOT stop an in-progress recording — that's the
        // whole point of RecordingForegroundService. Test tone and
        // playback have no reason to keep running once the app isn't visible.
        audioEngine.pauseForBackground()
        super.onStop()
    }

    override fun onDestroy() {
        audioEngine.release()
        super.onDestroy()
    }
}

/**
 * Sign-in/out row for the Songs (home) branch (Phase 13 rewrite of the old
 * `AccountRow`) -- an account is entirely optional here; this row is the
 * ONLY way to reach [AuthScreen], never a launch gate. Reads [authRepo] /
 * live each recomposition (not cached, since it's a thin wrapper over the
 * live Supabase client) -- recomposes automatically whenever [status]
 * changes reference, which happens on every [com.songnotes.core.data.AuthStateSignal]
 * bump (sign-in/out/unlock), the same reasoning the old `authVersion` local
 * existed for, now centralized in `SyncStatusRepository`.
 */
@Composable
private fun SyncHeader(
    authRepo: SupabaseAuthRepository,
    status: SyncStatus,
    syncController: SyncController,
    onSignInClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showSignOutDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        SignOutConfirmDialog(
            unsyncedCount = status.unsyncedTotal,
            onConfirm = {
                showSignOutDialog = false
                scope.launch {
                    authRepo.signOut()
                    syncController.disableSync()
                }
            },
            onDismiss = { showSignOutDialog = false },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // weight(1f) here (not on the Button) is the actual fix -- Row lays
        // out non-weighted children (the Button) at their natural size
        // FIRST, then gives whatever's left to this Column. Before this, the
        // email Text had no width constraint at all: a long address (found
        // live on-device -- "cryokinetic2468@gmail.com") consumed the Row's
        // full width, squeezing "Sign out" into a sliver so narrow its own
        // text wrapped one character per line, ballooning the button to ~470px
        // tall. maxLines/overflow below is the second half of the fix --
        // without it, a REALLY long email still overflows its own column.
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            if (authRepo.isSignedIn) {
                Text(
                    "Signed in as",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    authRepo.currentUserEmail.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    "Not signed in",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = {
            if (authRepo.isSignedIn) showSignOutDialog = true else onSignInClick()
        }) {
            Text(if (authRepo.isSignedIn) "Sign out" else "Enable sync")
        }
    }
}
