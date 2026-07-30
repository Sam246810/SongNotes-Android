package com.songnotes.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.songnotes.core.audio.AudioEngine

private enum class Screen { Diagnostics, Wizard, Manual, Scratchpad }

class MainActivity : ComponentActivity() {

    private val audioEngine = AudioEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // No navigation library wired up yet — just three screens, so a
            // plain enum toggle is the honest amount of infrastructure for
            // now (see docs/handoff/PHASE-00.md's "don't front-load" note).
            var screen by remember { mutableStateOf(Screen.Diagnostics) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.Wizard ->
                            CalibrationWizardScreen(engine = audioEngine, onDone = { screen = Screen.Diagnostics })
                        Screen.Manual ->
                            ManualCalibrationScreen(engine = audioEngine, onDone = { screen = Screen.Diagnostics })
                        Screen.Scratchpad ->
                            ScratchpadScreen(engine = audioEngine, onDone = { screen = Screen.Diagnostics })
                        Screen.Diagnostics -> Column(modifier = Modifier.fillMaxSize()) {
                            Button(
                                onClick = { screen = Screen.Scratchpad },
                                modifier = Modifier.padding(24.dp),
                            ) {
                                Text("Open scratchpad")
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
