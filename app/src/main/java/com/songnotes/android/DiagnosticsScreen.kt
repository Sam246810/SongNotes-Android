package com.songnotes.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.EngineCapabilities
import kotlinx.coroutines.delay

/**
 * Phase 0's entire product surface: open an output stream, play a 440 Hz test
 * tone, and show exactly what Oboe actually negotiated — this is the "hello
 * Oboe" screen the plan's Phase 0 Done bar (clean sine, zero xruns over 60s)
 * is checked against by hand on a real device.
 */
@Composable
fun DiagnosticsScreen(engine: AudioEngine) {
    var isPlaying by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(EngineCapabilities.unavailable()) }

    // No continuous state channel yet (that's Phase 1's direct-ByteBuffer
    // block, once there's a meter worth polling at 60 Hz). A slow poll here
    // is enough to watch the xrun counter during a 60s soak test.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
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
            if (isPlaying) {
                engine.stop()
                isPlaying = false
                caps = engine.capabilities()
            } else {
                isPlaying = engine.start()
                caps = engine.capabilities()
            }
        }) {
            Text(if (isPlaying) "Stop test tone" else "Play test tone")
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
