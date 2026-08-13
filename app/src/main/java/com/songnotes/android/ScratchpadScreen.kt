package com.songnotes.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.AudioRoute
import com.songnotes.core.audio.AudioRouteDetector
import com.songnotes.core.audio.CalibrationStore
import com.songnotes.core.audio.EngineState
import com.songnotes.core.audio.MultitrackClipSpec
import com.songnotes.core.audio.MultitrackProject
import com.songnotes.core.audio.MultitrackProjectStorage
import com.songnotes.core.audio.MultitrackTrackSpec
import com.songnotes.core.audio.RecordingInputPreference
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val kSampleRate = 48000

/** Whole-number BPM ("120") when it is one, else one decimal place ("120.5") — avoids "80.0" in the text field. */
private fun formatBpm(bpm: Double): String =
    if (bpm == bpm.toLong().toDouble()) bpm.toLong().toString() else "%.1f".format(bpm)

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
 *
 * UI shape: a [Scaffold] with the transport controls (record/play/add
 * track/export) pinned in a bottom bar rather than living at the bottom of
 * the scrolling content — with more than a couple of tracks the old layout
 * pushed those controls off-screen, which is the one thing on this screen
 * used on every single take. Tempo/time-signature/mic-route settings are
 * folded into a collapsible card (the plan's "DAW collapsible to tempo/BPM/
 * start-stop" polish item) since they're set once per session and don't
 * need to stay visible while recording.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScratchpadScreen(engine: AudioEngine, onDone: () -> Unit) {
    val context = LocalContext.current
    var project by remember { mutableStateOf(MultitrackProject()) }
    var selectedTrackIndex by remember { mutableStateOf<Int?>(null) }
    // Text-field buffer for editing project.bpm — kept in sync with it (see
    // the LaunchedEffect(Unit) load below), not the source of truth itself,
    // so bpm actually persists via MultitrackProjectStorage now instead of
    // resetting to a hardcoded default every time the app reopens.
    var bpmText by remember { mutableStateOf(formatBpm(project.bpm)) }
    var isRecording by remember { mutableStateOf(false) }
    var scrubFrame by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var engineState by remember { mutableStateOf(EngineState.idle()) }
    var settingsExpanded by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val storage = remember { MultitrackProjectStorage(context) }
    val inputPreference = remember { RecordingInputPreference(context) }
    var forceBuiltinMic by remember { mutableStateOf(false) }
    var currentInputRoute by remember { mutableStateOf<AudioRoute?>(null) }

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
        if (loaded != null) {
            project = loaded
            bpmText = formatBpm(loaded.bpm)
        }
    }

    fun useBuiltinMic() {
        forceBuiltinMic = true
        inputPreference.forceBuiltinMic = true
        scope.launch {
            val builtinMicId = withContext(Dispatchers.Default) { AudioRouteDetector(context).builtinMicDeviceId() } ?: 0
            engine.setPreferredInputDevice(builtinMicId)
        }
    }

    // "Use the connected device's own mic instead of the phone's." Only
    // reachable for a wired (including USB) route — Android's existing
    // automatic input routing already sends input to a connected wired
    // device once nothing pins it elsewhere, so setPreferredInputDevice(0/
    // unspecified) is enough. Bluetooth never reaches this function (see
    // the checkbox condition below): plain A2DP (music) Bluetooth audio has
    // no microphone path to any app at all, and the only way to get one —
    // an active SCO (call-audio) session — doesn't durably hold on this
    // engine's deliberately non-"communication" audio streams (see
    // docs/handoff/PHASE-10.md for the full investigation). Kept as its own
    // named function rather than inlined at the call site since the
    // checkbox and the initial route-restore LaunchedEffect both need it.
    fun useDeviceMic() {
        forceBuiltinMic = false
        inputPreference.forceBuiltinMic = false
        scope.launch { engine.setPreferredInputDevice(0) }
    }

    // Detects the input route once at screen-open (same point-in-time,
    // "good enough" check beginRecording()'s calibration-offset lookup
    // already does — not live-updated if the route changes while this
    // screen stays open) and re-applies a previously-saved mic-routing
    // choice, so it doesn't silently reset to default every time the
    // screen is reopened. A Bluetooth route always forces the phone mic —
    // there's no real device-mic alternative to offer it (see
    // useDeviceMic()'s doc comment) — regardless of any preference saved
    // before that was understood. Only relevant at all when something
    // other than the phone's own mic is actually connected, matching the
    // checkbox's own visibility condition below.
    LaunchedEffect(Unit) {
        val route = withContext(Dispatchers.Default) { AudioRouteDetector(context).currentInputRoute() }
        currentInputRoute = route
        if (route.isBluetooth) {
            useBuiltinMic()
        } else if (!route.isBuiltinMic) {
            if (inputPreference.forceBuiltinMic) useBuiltinMic() else useDeviceMic()
        } else {
            forceBuiltinMic = inputPreference.forceBuiltinMic
        }
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
        if (project.bpm <= 0.0) {
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
            engine, takeFile.absolutePath,
            targetIndex = targetIndex, backingTracksStartFrame = scrubFrame,
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
            val newClip = MultitrackClipSpec(buffer = takeSamples, startFrame = scrubFrame)
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

    fun onRecordPressed() {
        if (isRecording) {
            stopRecordingAndCommit()
        } else if (!hasRecordPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            beginRecording()
        }
    }

    LaunchedEffect(isRecording, isPlaying) {
        while (isRecording || isPlaying) {
            engineState = engine.state()
            if (isPlaying && !engineState.isPlaying) isPlaying = false
            delay(100)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scratchpad") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = "Done")
                    }
                },
                actions = {
                    IconButton(enabled = !isRecording, onClick = { persist(project, announce = true) }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                },
            )
        },
        bottomBar = {
            ScratchpadTransportBar(
                isRecording = isRecording,
                isPlaying = isPlaying,
                isExporting = isExporting,
                isArmed = engineState.isArmed,
                countInBeatsRemaining = engineState.countInBeatsRemaining,
                recordedSeconds = engineState.framesRecorded / kSampleRate,
                selectedTrackIndex = selectedTrackIndex,
                playbackFrame = engineState.playbackFrame,
                playbackTotalFrames = engineState.playbackTotalFrames,
                onAddTrack = {
                    project = project.addTrack()
                    persist(project)
                },
                onRecordPressed = ::onRecordPressed,
                onPlayToggle = ::togglePlayback,
                onExport = ::exportMixdown,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            statusMessage?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            SessionSettingsCard(
                expanded = settingsExpanded,
                onToggleExpanded = { settingsExpanded = !settingsExpanded },
                bpmText = bpmText,
                onBpmChange = { text ->
                    bpmText = text
                    val parsed = text.toDoubleOrNull()
                    if (parsed != null && parsed > 0.0) project = project.copy(bpm = parsed)
                },
                beatsPerBar = project.beatsPerBar,
                onBeatsPerBarChange = { project = project.copy(beatsPerBar = it) },
                enabled = !isRecording,
                route = currentInputRoute,
                forceBuiltinMic = forceBuiltinMic,
                onForceBuiltinMicChange = { if (it) useBuiltinMic() else useDeviceMic() },
            )
            Spacer(Modifier.height(12.dp))

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        if (project.tracks.isEmpty()) {
                            "Timeline"
                        } else {
                            "${project.tracks.size} track(s) · ${"%.1f".format(project.totalFrames / kSampleRate.toDouble())}s total"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))

                    if (project.tracks.isEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "No tracks yet — tap Record below to create one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Timeline(
                            engine = engine,
                            tracks = project.tracks,
                            totalFrames = project.totalFrames,
                            bpm = project.bpm,
                            beatsPerBar = project.beatsPerBar,
                            playbackFrame = if (isPlaying) engineState.playbackFrame.toLong() else null,
                            scrubFrame = scrubFrame,
                            onScrubChange = { scrubFrame = it },
                            enabled = !isRecording && !isPlaying,
                            onClipChange = { trackIndex, clipIndex, transform ->
                                project = project.withClipTransform(engine, trackIndex, clipIndex, transform)
                                persist(project)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Punch-in point: %.1fs".format(scrubFrame / kSampleRate.toDouble()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (project.tracks.isNotEmpty()) {
                Text("Tracks", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
            }

            // A plain Column, not LazyColumn: this list is always short (a
            // handful of tracks, not a virtualization-scale dataset), and the
            // whole screen is already inside a scrollable Column above — a
            // fixed-height LazyColumn nested in there needs a per-row height
            // guess that's easy to get wrong (an earlier version hard-coded
            // 92dp, which clipped the second track's Mute/Solo row off-screen
            // entirely once actually run on device). A plain Column just takes
            // each row's real height, no guessing.
            val soloedTrackExists = project.tracks.any { it.soloed }
            project.tracks.forEachIndexed { index, track ->
                TrackRow(
                    index = index,
                    track = track,
                    selected = selectedTrackIndex == index,
                    enabled = !isRecording && !isPlaying,
                    silencedBySolo = soloedTrackExists && !track.soloed,
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
            // Bottom breathing room so the last track row isn't flush against
            // the fixed transport bar.
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Tempo/time-signature/mic-route controls, folded behind a header that's
 * always visible (with a one-line summary) so the full form doesn't have to
 * stay on screen for a session that's already dialed in.
 */
@Composable
private fun SessionSettingsCard(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    bpmText: String,
    onBpmChange: (String) -> Unit,
    beatsPerBar: Int,
    onBeatsPerBarChange: (Int) -> Unit,
    enabled: Boolean,
    route: AudioRoute?,
    forceBuiltinMic: Boolean,
    onForceBuiltinMicChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Session", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (!expanded) {
                    Text(
                        "$bpmText BPM · ${beatsPerBar}/4",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = bpmText,
                        onValueChange = onBpmChange,
                        label = { Text("BPM") },
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(120.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Beats per bar", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                enabled = enabled && beatsPerBar > 1,
                                onClick = { onBeatsPerBarChange(beatsPerBar - 1) },
                            ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                            Text(
                                "$beatsPerBar",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                            IconButton(
                                enabled = enabled && beatsPerBar < 12,
                                onClick = { onBeatsPerBarChange(beatsPerBar + 1) },
                            ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                }

                // Only relevant when the currently-connected input route isn't
                // already the phone's own mic (a wired/USB headset, or a
                // Bluetooth device) — a plain pair of headphones with no mic
                // never shows this, since there's nothing for Android to route
                // input to besides the built-in mic in that case anyway.
                // Bluetooth gets an informational line only, not a toggle:
                // there's no real device-mic alternative to offer (see
                // useDeviceMic()'s doc comment in ScratchpadScreen), so the
                // phone mic is always used and always will be.
                if (route?.isBuiltinMic == false) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (route.isBluetooth) Icons.Filled.Bluetooth else Icons.Filled.Headset,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        if (route.isBluetooth) {
                            Text(
                                "Recording with the phone's mic — click still plays through ${route.label}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Column {
                                Text(
                                    if (forceBuiltinMic) {
                                        "Recording with the phone's mic — click still plays through ${route.label}"
                                    } else {
                                        "Recording with ${route.label}'s mic"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            FilterChip(
                                selected = forceBuiltinMic,
                                onClick = { onForceBuiltinMicChange(!forceBuiltinMic) },
                                enabled = enabled,
                                label = { Text("Force phone mic") },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Solo's unmistakable "this is what you'll hear" color — Ableton uses the same blue-for-solo convention. */
private val SoloBlue = Color(0xFF1E88E5)

/** Mute reads as "switched off" rather than an alert color, so it doesn't compete visually with Solo or Record/Delete's red. */
private val MuteGray = Color(0xFF616161)

@Composable
private fun TrackRow(
    index: Int,
    track: MultitrackTrackSpec,
    selected: Boolean,
    enabled: Boolean,
    /** True when some OTHER track is soloed, silencing this one even though [MultitrackTrackSpec.muted] is false. */
    silencedBySolo: Boolean,
    onSelect: () -> Unit,
    onGainChange: (Float) -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onSoloedChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val durationSeconds = (track.clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L) / kSampleRate.toDouble()
    val accent = trackColor(index)
    // Ableton's own technique for "you won't hear this right now": dim the
    // whole strip rather than relying on the mute/solo chips alone to
    // communicate audibility across every OTHER track on screen.
    val isAudible = !track.muted && !silencedBySolo
    Card(
        onClick = onSelect,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (isAudible) 1f else 0.5f),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Track ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${track.clips.size} clip(s) · %.1fs".format(durationSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(enabled = enabled, onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove track", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (track.gain > 0f) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Slider(
                    value = track.gain,
                    onValueChange = onGainChange,
                    valueRange = 0f..2f,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    "%.1f".format(track.gain),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(28.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                FilterChip(
                    selected = track.muted,
                    onClick = { onMutedChange(!track.muted) },
                    enabled = enabled,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MuteGray,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = enabled,
                        selected = track.muted,
                        selectedBorderColor = Color.Transparent,
                    ),
                    leadingIcon = {
                        Icon(
                            if (track.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(if (track.muted) "Muted" else "Mute") },
                )
                FilterChip(
                    selected = track.soloed,
                    onClick = { onSoloedChange(!track.soloed) },
                    enabled = enabled,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SoloBlue,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = enabled,
                        selected = track.soloed,
                        selectedBorderColor = Color.Transparent,
                    ),
                    leadingIcon = {
                        Icon(
                            if (track.soloed) Icons.Filled.Headset else Icons.Filled.HeadsetOff,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text(if (track.soloed) "Soloed" else "Solo") },
                )
            }
        }
    }
}

/**
 * Fixed bottom transport strip — record/play/add-track/export stay reachable
 * without scrolling regardless of how many tracks are in the project, since
 * these are the controls used on every single take.
 */
@Composable
private fun ScratchpadTransportBar(
    isRecording: Boolean,
    isPlaying: Boolean,
    isExporting: Boolean,
    isArmed: Boolean,
    countInBeatsRemaining: Int,
    recordedSeconds: Int,
    selectedTrackIndex: Int?,
    playbackFrame: Int,
    playbackTotalFrames: Int,
    onAddTrack: () -> Unit,
    onRecordPressed: () -> Unit,
    onPlayToggle: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val statusText = when {
                isArmed -> "Counting in: $countInBeatsRemaining"
                isRecording -> "Recording onto track ${(selectedTrackIndex ?: 0) + 1} — ${recordedSeconds}s so far"
                isPlaying -> "Playback: $playbackFrame / $playbackTotalFrames"
                else -> null
            }
            if (statusText != null) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRecording || isArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                TransportAction(
                    icon = Icons.Filled.Add,
                    label = "Add track",
                    enabled = !isRecording && !isPlaying,
                    onClick = onAddTrack,
                )
                TransportAction(
                    icon = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    label = if (isPlaying) "Stop" else "Play",
                    enabled = !isRecording,
                    size = 56.dp,
                    iconSize = 28.dp,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onPlayToggle,
                )
                TransportAction(
                    icon = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                    label = if (isRecording) "Stop" else "Record",
                    enabled = !isPlaying,
                    size = 64.dp,
                    iconSize = 30.dp,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = onRecordPressed,
                )
                TransportAction(
                    icon = Icons.Filled.SaveAlt,
                    label = if (isExporting) "Exporting…" else "Export",
                    enabled = !isRecording && !isPlaying && !isExporting,
                    loading = isExporting,
                    onClick = onExport,
                )
            }
        }
    }
}

@Composable
private fun TransportAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    loading: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(size),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
            } else {
                Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
