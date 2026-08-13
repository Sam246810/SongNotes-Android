package com.songnotes.android

import android.os.Bundle
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.data.KeySession
import com.songnotes.core.data.SongSyncWorker
import com.songnotes.core.data.SupabaseAuthRepository
import kotlinx.coroutines.launch

private enum class Screen { Diagnostics, Wizard, Manual, TapAlong, Scratchpad, Songs, SongEditor, Auth, Piano }

// FragmentActivity, not the usual bare ComponentActivity Compose apps default
// to -- BiometricPrompt (see DiagnosticsScreen.kt's device-wrap smoke test)
// needs one to host its internal dialog fragment. FragmentActivity extends
// ComponentActivity, so setContent {} and everything else here is unaffected.
class MainActivity : FragmentActivity() {

    private val audioEngine = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // No navigation library wired up yet — just several screens, so a
            // plain enum toggle is the honest amount of infrastructure for
            // now (see docs/handoff/PHASE-00.md's "don't front-load" note).
            //
            // Songs is the real home screen in every build, debug and
            // release alike (see docs/handoff/PHASE-11-prep-navigation.md) --
            // Diagnostics used to launch by default in every build, which
            // left real product screens (Scratchpad, Piano) reachable only
            // by routing through dev tooling with no gate around it.
            var screen by remember { mutableStateOf(Screen.Songs) }
            var editingSongId by remember { mutableStateOf<String?>(null) }
            val authRepo = remember { SupabaseAuthRepository() }
            // KeySession.isUnlocked() is a plain @Volatile-backed function, not
            // Compose state -- this trigger forces a recheck after
            // LockedAccountScreen unlocks/signs out, same "authVersion" idiom
            // AccountRow already uses below for the same underlying reason
            // (a live Supabase/KeySession read that Compose can't observe directly).
            var keySessionVersion by remember { mutableStateOf(0) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.Songs -> {
                            @Suppress("UNUSED_EXPRESSION") keySessionVersion
                            if (authRepo.isSignedIn && !KeySession.isUnlocked()) {
                                // Signed in, but no DEK for this process (e.g. it was
                                // killed since the last sign-in -- see
                                // LockedAccountScreen's doc comment) -- gate the whole
                                // Songs branch rather than showing an empty/broken list.
                                LockedAccountScreen(
                                    onUnlocked = { keySessionVersion++ },
                                    onSignOut = { keySessionVersion++ },
                                )
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    AccountRow(authRepo = authRepo, onSignInClick = { screen = Screen.Auth })
                                    // The only remaining way to reach Diagnostics (and
                                    // transitively Wizard/Manual/TapAlong) in a real
                                    // build. BuildConfig.DEBUG is a compile-time
                                    // constant, so once R8 is on (Phase 11), it treats
                                    // a release build's `if` as always-false and
                                    // strips this branch -- and everything only it
                                    // can reach -- out of the release APK for free.
                                    if (BuildConfig.DEBUG) {
                                        Button(
                                            onClick = { screen = Screen.Diagnostics },
                                            modifier = Modifier.padding(horizontal = 24.dp),
                                        ) {
                                            Text("Diagnostics")
                                        }
                                    }
                                    SongListScreen(
                                        onOpenSong = { id ->
                                            editingSongId = id
                                            screen = Screen.SongEditor
                                        },
                                        onOpenScratchpad = { screen = Screen.Scratchpad },
                                        onOpenPiano = { screen = Screen.Piano },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        Screen.SongEditor -> SongEditorScreen(
                            songId = editingSongId!!,
                            onDone = { screen = Screen.Songs },
                        )
                        Screen.Wizard ->
                            CalibrationWizardScreen(engine = audioEngine, onDone = { screen = Screen.Diagnostics })
                        Screen.Manual ->
                            ManualCalibrationScreen(engine = audioEngine, onDone = { screen = Screen.Diagnostics })
                        Screen.TapAlong ->
                            TapAlongCalibrationScreen(engine = audioEngine, onDone = { screen = Screen.Diagnostics })
                        Screen.Scratchpad ->
                            ScratchpadScreen(engine = audioEngine, onDone = { screen = Screen.Songs })
                        Screen.Auth -> AuthScreen(onDone = { screen = Screen.Songs })
                        Screen.Piano -> PianoScreen(engine = audioEngine, onDone = { screen = Screen.Songs })
                        // Debug-only: reachable exclusively via the
                        // BuildConfig.DEBUG-gated button above, never from a
                        // release build.
                        Screen.Diagnostics -> Column(modifier = Modifier.fillMaxSize()) {
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
 * Sign-in/out + manual sync trigger — lives on the Songs (home) branch now
 * rather than gated behind the dev-only Diagnostics screen, since real users
 * need account controls too. Reads [authRepo]'s auth state on every
 * recomposition (not cached) since it's a thin wrapper over the live
 * Supabase client (see [SupabaseAuthRepository]'s own doc comments) --
 * signing in via [onSignInClick]'s destination recomposes this naturally,
 * but sign-out doesn't change `screen`, hence [authVersion] as an explicit
 * recomposition trigger.
 */
@Composable
private fun AccountRow(authRepo: SupabaseAuthRepository, onSignInClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var authVersion by remember { mutableStateOf(0) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            @Suppress("UNUSED_EXPRESSION") authVersion
            Text(authRepo.currentUserEmail?.let { "Signed in as $it" } ?: "Not signed in")
            Button(onClick = {
                if (authRepo.isSignedIn) {
                    scope.launch {
                        authRepo.signOut()
                        authVersion++
                    }
                } else {
                    onSignInClick()
                }
            }) {
                Text(if (authRepo.isSignedIn) "Sign out" else "Sign in / Sign up")
            }
        }
        if (authRepo.isSignedIn) {
            // Manual trigger -- there's no periodic background sync yet (see
            // SongSyncWorker's doc comment), and nothing currently enqueues a
            // sync after a local edit, only right after sign-in. This is the
            // only way to push a just-saved edit without signing out and back in.
            Button(
                onClick = { SongSyncWorker.enqueueOneTime(context.applicationContext) },
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                Text("Sync now")
            }
        }
    }
}
