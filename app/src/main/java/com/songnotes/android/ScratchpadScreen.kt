package com.songnotes.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.AudioRouteDetector
import com.songnotes.core.audio.CalibrationStore
import com.songnotes.core.audio.EngineState
import com.songnotes.core.audio.MultitrackClipSpec
import com.songnotes.core.audio.MultitrackProject
import com.songnotes.core.audio.MultitrackProjectStorage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val kSampleRate = 48000

/**
 * The plan's "Scratchpad product UI" (nominally Phase 10 territory),
 * front-loaded here now that Phase 4's internals — real-time multitrack
 * playback, real overdub recording, punch-in splicing, and
 * [MultitrackProject] as the state to drive all of it — are built and
 * verified. Deliberately minimal: one track list, one record target at a
 * time, punch-in always starts at project frame 0 (the whole song plays
 * back as the backing track for every take — no scrubbing/seeking to an
 * arbitrary punch-in point yet, since there's no timeline UI to pick one
 * from). A real DAW-grade editor is out of scope for this pass; this is
 * "does the engine work reachable from actual UI," the same bar every
 * other phase's diagnostics-screen verification held itself to.
 *
 * Persisted via [MultitrackProjectStorage]: auto-loaded once when this
 * screen first composes, auto-saved after every mutation that matters
 * (a committed recording, adding/removing a track) so closing the app
 * mid-session doesn't lose a take, plus an explicit "Save" button for
 * gain/mute/solo tweaks — those aren't auto-saved on every slider drag
 * tick (that would mean a file write per pixel of drag), so a deliberate
 * Save is how those specifically get persisted.
 */
@Composable
fun ScratchpadScreen(engine: AudioEngine, onDone: () -> Unit) {
    val context = LocalContext.current
    var project by remember { mutableStateOf(MultitrackProject()) }
    var selectedTrackIndex by remember { mutableStateOf<Int?>(null) }
    var bpmText by remember { mutableStateOf("80") }
    var isRecording by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var engineState by remember { mutableStateOf(EngineState.idle()) }
    val scope = rememberCoroutineScope()
    val storage = remember { MultitrackProjectStorage(context) }

    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val takeFile = remember {
        File(context.filesDir, "takes/scratchpad_take.f32").also { it.parentFile?.mkdirs() }
    }

    // Auto-load once when the screen first composes — before this, the
    // scratchpad started empty every time, even with a previously saved
    // project sitting on disk.
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.Default) { storage.load() }
        if (loaded != null) project = loaded
    }

    // Nothing previously stopped an in-progress recording if the user left
    // this screen mid-take (navigating away, or the system tearing the
    // Activity down) — the native writer thread and RT engine mode were
    // left dangling, since only the explicit "Stop recording" button ever
    // called engine.stopRecording(). A later armRecording() call does
    // clean up a stale session first, but nothing guaranteed one would
    // ever be made, and playback/export attempted against a still-Recording
    // engine is a real, not-yet-understood-crash-risk state this project
    // should just never be able to enter. isRecording is deliberately not
    // a DisposableEffect key — it only needs to run once, on final
    // disposal, not every time recording starts/stops.
    DisposableEffect(Unit) {
        onDispose {
            if (isRecording) {
                engine.stopRecording()
                context.stopService(Intent(context, RecordingForegroundService::class.java))
            }
        }
    }

    fun persist(toSave: MultitrackProject, announce: Boolean = false) {
        scope.launch {
            withContext(Dispatchers.Default) { storage.save(toSave) }
            if (announce) statusMessage = "Saved."
        }
    }

    fun beginRecording() {
        val bpm = bpmText.toDoubleOrNull()
        if (bpm == null || bpm <= 0.0) {
            statusMessage = "Enter a valid BPM before recording."
            return
        }
        val targetIndex = selectedTrackIndex ?: run {
            project = project.addTrack()
            project.tracks.size - 1
        }
        selectedTrackIndex = targetIndex

        val route = AudioRouteDetector(context).currentInputRoute()
        val calibrationOffsetFrames = CalibrationStore(context).load(route.routeKey)?.offsetFrames ?: 0.0

        context.startForegroundService(Intent(context, RecordingForegroundService::class.java))
        val armed = project.armOverdub(
            engine, takeFile.absolutePath, bpm, beatsPerBar = 4, countInBeats = 4,
            targetIndex = targetIndex, backingTracksStartFrame = 0L,
            calibrationOffsetFrames = calibrationOffsetFrames,
        )
        if (!armed) {
            context.stopService(Intent(context, RecordingForegroundService::class.java))
            statusMessage = "Failed to arm recording — see Diagnostics for Last error."
            return
        }
        isRecording = true
        statusMessage = null
    }

    fun stopRecordingAndCommit() {
        engine.stopRecording()
        context.stopService(Intent(context, RecordingForegroundService::class.java))
        val targetIndex = selectedTrackIndex
        scope.launch {
            delay(150) // let the writer thread flush its last buffered frames to disk
            val takeBytes = takeFile.readBytes()
            isRecording = false
            if (takeBytes.isEmpty() || targetIndex == null) {
                statusMessage = "Recording produced no audio."
                return@launch
            }
            val takeSamples = FloatArray(takeBytes.size / 4)
            ByteBuffer.wrap(takeBytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(takeSamples)
            val newClip = MultitrackClipSpec(buffer = takeSamples, startFrame = 0L)
            project = project.withPunchIn(engine, targetIndex, newClip)
            persist(project)
            statusMessage = "Recorded %.1fs onto track %d.".format(
                takeSamples.size / kSampleRate.toDouble(), targetIndex + 1,
            )
        }
    }

    fun togglePlayback() {
        if (isPlaying) {
            engine.stopPlayback()
            isPlaying = false
            return
        }
        if (project.tracks.isEmpty() || project.totalFrames == 0L) {
            statusMessage = "Nothing to play yet — record onto a track first."
            return
        }
        isPlaying = project.play(engine)
        statusMessage = if (isPlaying) null else "Playback failed to start — see Diagnostics for Last error."
    }

    fun exportMixdown() {
        if (project.tracks.isEmpty() || project.totalFrames == 0L) {
            statusMessage = "Nothing to export yet — record onto a track first."
            return
        }
        isExporting = true
        val outFile = File(context.filesDir, "exports/scratchpad_mixdown.wav").also { it.parentFile?.mkdirs() }
        scope.launch {
            val ok = withContext(Dispatchers.Default) {
                project.exportToWav(engine, outFile.absolutePath, kSampleRate)
            }
            isExporting = false
            statusMessage = if (ok) "Exported to ${outFile.absolutePath}" else "Export failed."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasRecordPermission = granted
        if (granted) beginRecording() else statusMessage = "Microphone permission is required to record."
    }

    LaunchedEffect(isRecording, isPlaying) {
        while (isRecording || isPlaying) {
            engineState = engine.state()
            if (isPlaying && !engineState.isPlaying) isPlaying = false
            delay(100)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scratchpad", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Button(onClick = onDone) { Text("Done") }
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = bpmText,
            onValueChange = { bpmText = it },
            label = { Text("BPM") },
            enabled = !isRecording,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(16.dp))

        Text(
            if (project.tracks.isEmpty()) {
                "No tracks yet — tap Record to create one."
            } else {
                "${project.tracks.size} track(s), ${"%.1f".format(project.totalFrames / kSampleRate.toDouble())}s total"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        Timeline(
            engine = engine,
            tracks = project.tracks,
            totalFrames = project.totalFrames,
            playbackFrame = if (isPlaying) engineState.playbackFrame.toLong() else null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        // A plain Column, not LazyColumn: this list is always short (a
        // handful of tracks, not a virtualization-scale dataset), and the
        // whole screen is already inside a scrollable Column above — a
        // fixed-height LazyColumn nested in there needs a per-row height
        // guess that's easy to get wrong (an earlier version hard-coded
        // 92dp, which clipped the second track's Mute/Solo row off-screen
        // entirely once actually run on device). A plain Column just takes
        // each row's real height, no guessing.
        project.tracks.forEachIndexed { index, track ->
            TrackRow(
                index = index,
                track = track,
                selected = selectedTrackIndex == index,
                enabled = !isRecording && !isPlaying,
                onSelect = { selectedTrackIndex = if (selectedTrackIndex == index) null else index },
                onGainChange = { project = project.withTrackGain(index, it) },
                onMutedChange = { project = project.withTrackMuted(index, it) },
                onSoloedChange = { project = project.withTrackSoloed(index, it) },
                onRemove = {
                    project = project.removeTrack(index)
                    // selectedTrackIndex is a list position, not a stable
                    // track identity — removing a track shifts every later
                    // index down by one, so a selection AFTER the removed
                    // track needs to shift with it, not just get cleared
                    // when it happens to equal the removed index.
                    selectedTrackIndex = when {
                        selectedTrackIndex == index -> null
                        selectedTrackIndex != null && selectedTrackIndex!! > index -> selectedTrackIndex!! - 1
                        else -> selectedTrackIndex
                    }
                    persist(project)
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        Row {
            Button(
                enabled = !isRecording && !isPlaying,
                onClick = {
                    project = project.addTrack()
                    persist(project)
                },
            ) {
                Text("Add track")
            }
            Spacer(Modifier.width(12.dp))
            Button(enabled = !isRecording, onClick = { persist(project, announce = true) }) {
                Text("Save")
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        if (engineState.isArmed) {
            Text("Counting in: ${engineState.countInBeatsRemaining}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
        } else if (isRecording) {
            Text(
                "Recording onto track ${(selectedTrackIndex ?: 0) + 1} — " +
                    "${engineState.framesRecorded / kSampleRate}s so far",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
        }

        Row {
            Button(
                enabled = !isPlaying,
                onClick = {
                    if (isRecording) {
                        stopRecordingAndCommit()
                    } else if (!hasRecordPermission) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        beginRecording()
                    }
                },
            ) {
                Text(if (isRecording) "Stop recording" else "Record" + (selectedTrackIndex?.let { " (track ${it + 1})" } ?: " (new track)"))
            }
            Spacer(Modifier.width(12.dp))
            Button(enabled = !isRecording, onClick = { togglePlayback() }) {
                Text(if (isPlaying) "Stop" else "Play")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(enabled = !isRecording && !isPlaying && !isExporting, onClick = { exportMixdown() }) {
            Text(if (isExporting) "Exporting..." else "Export mixdown to WAV")
        }

        if (isPlaying) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Playback: ${engineState.playbackFrame} / ${engineState.playbackTotalFrames}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        statusMessage?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TrackRow(
    index: Int,
    track: com.songnotes.core.audio.MultitrackTrackSpec,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onGainChange: (Float) -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onSoloedChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val durationSeconds = (track.clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L) / kSampleRate.toDouble()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Track ${index + 1} (${track.clips.size} clip(s), %.1fs)".format(durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(enabled = enabled, onClick = onRemove) { Text("Remove") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gain", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = track.gain,
                onValueChange = onGainChange,
                valueRange = 0f..2f,
                enabled = enabled,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("%.1f".format(track.gain), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = track.muted, onCheckedChange = onMutedChange, enabled = enabled)
            Text("Mute", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(16.dp))
            Checkbox(checked = track.soloed, onCheckedChange = onSoloedChange, enabled = enabled)
            Text("Solo", style = MaterialTheme.typography.bodySmall)
        }
    }
}
