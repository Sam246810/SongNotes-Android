package com.songnotes.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.EngineCapabilities
import com.songnotes.core.audio.EngineState
import kotlinx.coroutines.delay
import java.io.File

/**
 * Phase 0's "hello Oboe" tone test, plus Phase 1's real duplex record/
 * playback round trip. Still one screen, still no navigation — there's
 * nothing yet to navigate to.
 */
@Composable
fun DiagnosticsScreen(engine: AudioEngine) {
    var isTonePlaying by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(EngineCapabilities.unavailable()) }

    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // The engine always opens a duplex pair (output-master pattern), even
    // for tone-only mode, so starting the tone needs RECORD_AUDIO too —
    // without it, input requestStart() fails and startTestTone() no-ops.
    val tonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            isTonePlaying = engine.startTestTone()
            caps = engine.capabilities()
        }
    }

    LaunchedEffect(isTonePlaying) {
        while (isTonePlaying) {
            caps = engine.capabilities()
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text("SongNotes — Audio Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Opens an output stream, plays a 440 Hz test tone, reports what it actually got.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))

        Button(onClick = {
            if (isTonePlaying) {
                engine.stopTestTone()
                isTonePlaying = false
                caps = engine.capabilities()
            } else if (!hasRecordPermission) {
                tonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                isTonePlaying = engine.startTestTone()
                caps = engine.capabilities()
            }
        }) {
            Text(if (isTonePlaying) "Stop test tone" else "Play test tone")
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        CapabilityRow("Audio API", caps.audioApi)
        CapabilityRow("Sample rate", "${caps.sampleRate} Hz")
        CapabilityRow("Frames per burst", "${caps.framesPerBurst}")
        CapabilityRow("Channel count", "${caps.channelCount}")
        CapabilityRow("Format", caps.format)
        CapabilityRow("Sharing mode", caps.sharingMode)
        CapabilityRow("Performance mode", caps.performanceMode)
        CapabilityRow("MMap (fast path)", if (caps.isMMapUsed) "yes" else "no")
        CapabilityRow("XRun count", "${caps.xRunCount}")

        caps.lastError?.let { error ->
            Spacer(Modifier.height(16.dp))
            Text("Last error: $error", color = MaterialTheme.colorScheme.error)
        }

        RecordPlaybackSection(engine)
    }
}

@Composable
private fun RecordPlaybackSection(engine: AudioEngine) {
    val context = LocalContext.current
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var isPolling by remember { mutableStateOf(false) }
    var engineState by remember { mutableStateOf(EngineState.idle()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var bpmText by remember { mutableStateOf("80") }

    val takeFile = remember {
        File(context.filesDir, "takes/phase2_test.f32").also { it.parentFile?.mkdirs() }
    }
    // Recomputed each recomposition on purpose — cheap, and file existence
    // can change from a button click above without a full screen rebuild.
    val takeFileExists = takeFile.exists()

    fun beginRecording() {
        val bpm = bpmText.toDoubleOrNull()
        if (bpm == null || bpm <= 0.0) {
            statusMessage = "Enter a valid BPM before recording."
            return
        }
        context.startForegroundService(Intent(context, RecordingForegroundService::class.java))
        // 4 beats of count-in, 4/4 time — fixed for this diagnostic screen;
        // a real UI for these lands with Phase 8/10.
        if (engine.armRecording(takeFile.absolutePath, bpm, beatsPerBar = 4, countInBeats = 4)) {
            engineState = engine.state()
            isPolling = true
            statusMessage = null
        } else {
            statusMessage = "Failed to arm recording — see Last error above."
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            beginRecording()
        } else {
            statusMessage = "Microphone permission is required to record."
        }
    }

    LaunchedEffect(isPolling) {
        while (isPolling) {
            engineState = engine.state()
            if (!engineState.isArmed && !engineState.isRecording && !engineState.isPlaying) {
                isPolling = false
            }
            delay(100)
        }
    }

    Spacer(Modifier.height(32.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Record & Playback (Phase 2)", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        "4-beat count-in against a metronome, then records real mic input to ${takeFile.name}. " +
            "Plays back through the same duplex engine, with the pre-roll already trimmed off.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(16.dp))

    OutlinedTextField(
        value = bpmText,
        onValueChange = { bpmText = it },
        label = { Text("BPM") },
        enabled = !engineState.isArmed && !engineState.isRecording,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Spacer(Modifier.height(12.dp))

    Row {
        Button(
            enabled = !engineState.isArmed && !engineState.isRecording && !engineState.isPlaying,
            onClick = {
                if (!hasRecordPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    beginRecording()
                }
            },
        ) {
            Text("Arm & record")
        }

        Spacer(Modifier.width(12.dp))

        Button(
            enabled = engineState.isArmed || engineState.isRecording,
            onClick = {
                engine.stopRecording()
                context.stopService(Intent(context, RecordingForegroundService::class.java))
                engineState = engine.state()
                isPolling = false
            },
        ) {
            Text("Stop recording")
        }
    }

    if (engineState.isArmed) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Counting in: ${engineState.countInBeatsRemaining}",
            style = MaterialTheme.typography.titleMedium,
        )
    }

    Spacer(Modifier.height(8.dp))

    Row {
        Button(
            enabled = !engineState.isRecording && !engineState.isPlaying && takeFileExists,
            onClick = {
                if (engine.startPlayback(takeFile.absolutePath)) {
                    engineState = engine.state()
                    isPolling = true
                    statusMessage = null
                } else {
                    statusMessage = "Failed to start playback — see Last error above."
                }
            },
        ) {
            Text("Play last take")
        }

        Spacer(Modifier.width(12.dp))

        Button(
            enabled = engineState.isPlaying,
            onClick = {
                engine.stopPlayback()
                engineState = engine.state()
                isPolling = false
            },
        ) {
            Text("Stop playback")
        }
    }

    Spacer(Modifier.height(16.dp))
    CapabilityRow("Armed (counting in)", if (engineState.isArmed) "yes" else "no")
    CapabilityRow("Recording", if (engineState.isRecording) "yes" else "no")
    CapabilityRow("Playing", if (engineState.isPlaying) "yes" else "no")
    CapabilityRow("Frames recorded", "${engineState.framesRecorded}")
    CapabilityRow("Playback position", "${engineState.playbackFrame} / ${engineState.playbackTotalFrames}")
    CapabilityRow("Frames dropped", "${engineState.framesDropped}")

    statusMessage?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CapabilityRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
