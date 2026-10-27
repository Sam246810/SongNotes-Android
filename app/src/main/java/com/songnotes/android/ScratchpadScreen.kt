package com.songnotes.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.songnotes.core.audio.RecordingInputPreference
import java.io.File
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val kSampleRate = 48000

/**
 * Longest single take; recording stops and saves itself here. Bounded by
 * memory, not storage: a finished take is held whole as a FloatArray
 * (~11.5 MB per minute at 48 kHz mono), along with every other take in the
 * project, so a much longer cap needs takes streamed from disk instead.
 */
private const val kMaxTakeSeconds = 10 * 60

/** How long before [kMaxTakeSeconds] the transport starts counting down. */
private const val kTakeLimitWarnSeconds = 60

/** How many edits Undo can step back through. */
private const val kUndoDepth = 30

/** Capture starts this long before the downbeat (`kPreRollSeconds` in audio_engine.h); framesRecorded includes it. */
private const val kPreRollFrames = 36_000

/** The engine state poll interval, which is also one live-waveform peak of a take being recorded. */
private const val kPollMs = 100L

/**
 * A song's Scratchpad: its own multitrack demo, laid out like Ableton 12's
 * Arrangement view (see [ArrangementView]) under an Ableton-style control
 * bar (tempo, time signature, metronome with count-in, grid, transport,
 * position, undo/redo). Landscape by default with a portrait option.
 *
 * Persisted via [MultitrackProjectStorage], scoped to [songId] — every song
 * has its own demo, stored on the phone, offline, with or without an
 * account. Auto-loaded when this composable first composes and auto-saved
 * (debounced) after every edit through `commit()`, which also feeds the
 * undo/redo history.
 *
 * [SongEditorScreen] composes this UNCONDITIONALLY and toggles [visible], so
 * a take keeps rolling while the user looks at the lyrics: hiding never
 * stops a recording; only Record/Stop does, or the editor itself closing
 * (the `DisposableEffect` below). While hidden and recording, a small bubble
 * shows instead (tap: [onExpand]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScratchpadScreen(
    engine: AudioEngine,
    songId: String,
    visible: Boolean,
    /**
     * The song's chart tempo from the lyrics page (0 = unset). It's the
     * source of truth for tempo: the effect below keeps the project's bpm
     * following it live. One-directional by request.
     */
    songBpm: Int,
    /** Shown in the recording notification ("Scratchpad · <title>"). */
    songTitle: String = "",
    onDone: () -> Unit,
    onExpand: () -> Unit,
    onRecordingChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    // A brand-new project starts with one track, armed, so Record never has
    // to conjure one (direct feedback). Removing every track is allowed.
    var project by remember { mutableStateOf(MultitrackProject().addTrack()) }
    var armedTrack by remember { mutableStateOf<Int?>(0) }
    var selectedClip by remember { mutableStateOf<ClipRef?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var insertFrame by remember { mutableStateOf(0L) }
    var recordStartFrame by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var engineState by remember { mutableStateOf(EngineState.idle()) }
    var snapOn by remember { mutableStateOf(true) }
    var tempoDialogOpen by remember { mutableStateOf(false) }
    val livePeaks = remember { mutableStateListOf<Float>() }
    val scope = rememberCoroutineScope()
    val storage = remember(songId) { MultitrackProjectStorage(context, songId) }
    val prefs = remember { ScratchpadPreferences(context) }
    var portraitMode by remember { mutableStateOf(prefs.portrait) }
    val inputPreference = remember { RecordingInputPreference(context) }
    var forceBuiltinMic by remember { mutableStateOf(false) }
    var currentInputRoute by remember { mutableStateOf<AudioRoute?>(null) }
    // Per visit: it's about what's connected right now, not a tip to silence forever.
    var bluetoothNoticeDismissed by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }
    BackHandler(enabled = showCalibration) { showCalibration = false }

    // False until the saved project (if any) has been read back, so the
    // auto-save never writes the placeholder over a real project.
    var projectLoaded by remember { mutableStateOf(false) }
    // Whole-project snapshots. Cheap: the project is immutable and clips
    // share their audio buffers. Per session only.
    val undoStack = remember { mutableStateListOf<MultitrackProject>() }
    val redoStack = remember { mutableStateListOf<MultitrackProject>() }
    var lastCommitKey by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<Job?>(null) }

    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val takeFile = remember {
        File(context.filesDir, "takes/scratchpad_take.f32").also { it.parentFile?.mkdirs() }
    }

    LaunchedEffect(songId) {
        val loaded = withContext(Dispatchers.Default) { storage.load() }
        if (loaded != null) {
            // Shown exactly as left (including empty); bpm follows the chart.
            project = if (songBpm > 0) loaded.copy(bpm = songBpm.toDouble()) else loaded
            armedTrack = if (loaded.tracks.isEmpty()) null else 0
        } else if (songBpm > 0) {
            project = project.copy(bpm = songBpm.toDouble())
        }
        projectLoaded = true
    }

    // The engine's Metronome switch follows the project's, live.
    LaunchedEffect(project.metronomeOn) { engine.setMetronomeOn(project.metronomeOn) }

    // Debounced auto-save on AppScope, so leaving right after an edit can't
    // cancel the write; storage serializes saves.
    LaunchedEffect(project, projectLoaded) {
        if (!projectLoaded) return@LaunchedEffect
        val snapshot = project
        pendingSave?.cancel()
        pendingSave = AppScope.io.launch {
            delay(500)
            storage.save(snapshot)
        }
    }

    LaunchedEffect(songBpm) {
        if (songBpm > 0 && project.bpm != songBpm.toDouble()) project = project.copy(bpm = songBpm.toDouble())
    }

    // Landscape by default while the Scratchpad is on screen (the manifest's
    // configChanges keep rotation from recreating the activity, which owns
    // the audio engine); every other screen stays portrait.
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(visible, portraitMode) {
        activity?.requestedOrientation = when {
            !visible -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            portraitMode -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }

    fun useBuiltinMic() {
        forceBuiltinMic = true
        inputPreference.forceBuiltinMic = true
        scope.launch {
            val builtinMicId = withContext(Dispatchers.Default) { AudioRouteDetector(context).builtinMicDeviceId() } ?: 0
            engine.setPreferredInputDevice(builtinMicId)
        }
    }

    // Wired/USB only: let Android's default routing use the device's own
    // mic. Bluetooth never gets here — its mic needs an SCO session that
    // doesn't hold on this engine's streams (see docs/handoff/PHASE-10.md).
    fun useDeviceMic() {
        forceBuiltinMic = false
        inputPreference.forceBuiltinMic = false
        scope.launch { engine.setPreferredInputDevice(0) }
    }

    // Route check once at open, re-applying the saved mic choice; Bluetooth
    // always uses the phone mic.
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

    // Leaving the editor mid-take stops the engine cleanly rather than
    // leaving its writer thread and RT mode dangling.
    DisposableEffect(Unit) {
        onDispose {
            if (isRecording) {
                engine.stopRecording()
                RecordingForegroundService.stop(context)
            }
        }
    }

    /** Applies a user edit: records the current project for undo, then replaces it (auto-save follows). */
    fun commit(next: MultitrackProject, coalesceKey: String? = null) {
        if (next == project) return
        if (coalesceKey == null || coalesceKey != lastCommitKey) {
            undoStack.add(project)
            if (undoStack.size > kUndoDepth) undoStack.removeAt(0)
        }
        redoStack.clear()
        lastCommitKey = coalesceKey
        project = next
    }

    // Tempo follows the chart BPM, so a restored snapshot keeps the current tempo.
    fun restore(snapshot: MultitrackProject) {
        project = if (songBpm > 0) snapshot.copy(bpm = songBpm.toDouble()) else snapshot
        lastCommitKey = null
        selectedClip = null
        armedTrack = when {
            project.tracks.isEmpty() -> null
            else -> (armedTrack ?: 0).coerceAtMost(project.tracks.lastIndex)
        }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.add(project)
        restore(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.add(project)
        restore(next)
    }

    fun beginRecording() {
        if (project.tracks.isEmpty()) return
        if (project.bpm <= 0.0) {
            statusMessage = "Set a tempo before recording."
            return
        }
        val targetIndex = (armedTrack ?: 0).coerceAtMost(project.tracks.lastIndex)
        armedTrack = targetIndex
        selectedClip = null

        val route = AudioRouteDetector(context).currentInputRoute()
        val calibrationOffsetFrames = CalibrationStore(context).load(route.routeKey)?.offsetFrames ?: 0.0

        RecordingForegroundService.start(context, songTitle)
        val armed = project.armOverdub(
            engine, takeFile.absolutePath,
            targetIndex = targetIndex, backingTracksStartFrame = insertFrame,
            calibrationOffsetFrames = calibrationOffsetFrames,
        )
        if (!armed) {
            RecordingForegroundService.stop(context)
            statusMessage = "Couldn't start recording — see Diagnostics for the last error."
            return
        }
        recordStartFrame = insertFrame
        livePeaks.clear()
        isRecording = true
        statusMessage = null
    }

    fun stopRecordingAndCommit(hitTakeLimit: Boolean = false) {
        engine.stopRecording()
        RecordingForegroundService.stop(context)
        val targetIndex = armedTrack
        scope.launch {
            delay(150) // let the writer thread flush its last buffered frames to disk
            val takeSamples = withContext(Dispatchers.IO) { MultitrackProjectStorage.readF32(takeFile) }
            isRecording = false
            livePeaks.clear()
            if (takeSamples.isEmpty() || targetIndex == null || targetIndex > project.tracks.lastIndex) {
                statusMessage = "Recording produced no audio."
                return@launch
            }
            val newClip = MultitrackClipSpec(buffer = takeSamples, startFrame = recordStartFrame)
            commit(project.withPunchIn(engine, targetIndex, newClip))
            val name = project.tracks[targetIndex].name
            statusMessage = if (hitTakeLimit) {
                "Reached the ${kMaxTakeSeconds / 60}-minute take limit — take saved on $name."
            } else {
                "Recorded %.1fs on %s.".format(takeSamples.size / kSampleRate.toDouble(), name)
            }
        }
    }

    fun togglePlayback() {
        if (isPlaying) {
            engine.stopPlayback()
            isPlaying = false
            return
        }
        if (project.tracks.isEmpty() || project.totalFrames == 0L) {
            statusMessage = "Nothing to play yet — record a take first."
            return
        }
        // Ableton plays from the insert marker; past the end, from the top.
        val from = if (insertFrame < project.totalFrames) insertFrame else 0L
        isPlaying = project.play(engine, from)
        if (!isPlaying) statusMessage = "Playback failed to start — see Diagnostics for the last error."
    }

    fun exportMixdown() {
        if (project.tracks.isEmpty() || project.totalFrames == 0L) {
            statusMessage = "Nothing to export yet — record a take first."
            return
        }
        isExporting = true
        val outFile = File(context.filesDir, "exports/scratchpad_mixdown.wav").also { it.parentFile?.mkdirs() }
        scope.launch {
            val ok = withContext(Dispatchers.Default) { project.exportToWav(engine, outFile.absolutePath, kSampleRate) }
            isExporting = false
            statusMessage = if (ok) "Exported to ${outFile.absolutePath}" else "Export failed."
        }
    }

    // Notification permission is asked once, on the first Record, so the
    // status bar can show the mic + timer while a take rolls. Recording goes
    // ahead whatever the answer.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        beginRecording()
    }

    fun beginRecordingAskingForNotificationsOnce() {
        val needsAsk = Build.VERSION.SDK_INT >= 33 && !prefs.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            prefs.notificationPermissionAsked = true
            statusMessage = "Allow notifications to see a recording timer in the status bar."
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginRecording()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasRecordPermission = granted
        if (granted) beginRecordingAskingForNotificationsOnce() else statusMessage = "Microphone permission is required to record."
    }

    fun onRecordPressed() {
        when {
            isRecording -> stopRecordingAndCommit()
            !hasRecordPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else -> beginRecordingAskingForNotificationsOnce()
        }
    }

    LaunchedEffect(isRecording, isPlaying) {
        while (isRecording || isPlaying) {
            engineState = engine.state()
            if (isPlaying && !engineState.isPlaying) isPlaying = false
            if (isRecording && !engineState.isArmed && engineState.framesRecorded > kPreRollFrames) {
                livePeaks.add(engineState.inputPeakMilli / 1000f)
            }
            // Runs while minimized too, so the cap holds from the lyrics.
            if (isRecording && engineState.framesRecorded >= kMaxTakeSeconds * kSampleRate) {
                stopRecordingAndCommit(hitTakeLimit = true)
                break
            }
            delay(kPollMs)
        }
    }

    LaunchedEffect(isRecording) { onRecordingChanged(isRecording) }

    // Status lines fade on their own.
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(4000)
            statusMessage = null
        }
    }

    val recordedSeconds = engineState.framesRecorded / kSampleRate
    // Where the built-in mic is (no permission needed); null if the phone
    // doesn't say. Shown as a pointer while a take is armed or rolling.
    val micNatural = remember { locateBuiltinMic(context) }
    val usingBuiltinMic = currentInputRoute.let { it == null || it.isBuiltinMic || it.isBluetooth } || forceBuiltinMic

    Box(modifier = Modifier.fillMaxSize()) {
        if (visible) {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val editsEnabled = !isRecording && !isPlaying
            val framesPerBeat = kSampleRate * 60.0 / project.bpm.coerceAtLeast(1.0)
            val positionFrame = when {
                isPlaying -> engineState.playbackFrame.toLong()
                isRecording -> recordStartFrame + (engineState.framesRecorded - kPreRollFrames).coerceAtLeast(0)
                else -> insertFrame
            }
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    ScratchpadControlBar(
                        compact = !landscape,
                        isRecording = isRecording,
                        isArmed = engineState.isArmed && isRecording,
                        countInBeatsRemaining = engineState.countInBeatsRemaining,
                        isPlaying = isPlaying,
                        canPlay = project.totalFrames > 0L,
                        canRecord = project.tracks.isNotEmpty(),
                        bpm = project.bpm,
                        beatsPerBar = project.beatsPerBar,
                        metronomeOn = project.metronomeOn,
                        countInBars = project.countInBars,
                        snapOn = snapOn,
                        position = formatPosition(positionFrame, framesPerBeat, project.beatsPerBar),
                        time = if (isRecording && recordedSeconds >= kMaxTakeSeconds - kTakeLimitWarnSeconds) {
                            "stops in ${(kMaxTakeSeconds - recordedSeconds).coerceAtLeast(0)}s"
                        } else {
                            formatClock(positionFrame)
                        },
                        canUndo = editsEnabled && undoStack.isNotEmpty(),
                        canRedo = editsEnabled && redoStack.isNotEmpty(),
                        editsEnabled = editsEnabled,
                        isExporting = isExporting,
                        portraitMode = portraitMode,
                        onBack = onDone,
                        onTempoClick = { tempoDialogOpen = true },
                        onBeatsPerBarChange = { commit(project.copy(beatsPerBar = it)) },
                        onMetronomeToggle = { commit(project.copy(metronomeOn = !project.metronomeOn)) },
                        onCountInChange = { commit(project.copy(countInBars = it)) },
                        onSnapToggle = { snapOn = !snapOn },
                        onPlay = ::togglePlayback,
                        onRecord = ::onRecordPressed,
                        onUndo = ::undo,
                        onRedo = ::redo,
                        onExport = ::exportMixdown,
                        onToggleOrientation = { portraitMode = !portraitMode; prefs.portrait = portraitMode },
                        onCalibrate = { showCalibration = true },
                        onTutorial = { /* §7 */ },
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArrangementView(
                            engine = engine,
                            tracks = project.tracks,
                            bpm = project.bpm,
                            beatsPerBar = project.beatsPerBar,
                            insertFrame = insertFrame,
                            onInsertFrameChange = { insertFrame = it },
                            playheadFrame = if (isPlaying) engineState.playbackFrame.toLong() else null,
                            recording = if (isRecording && !engineState.isArmed) {
                                armedTrack?.let {
                                    RecordingPreview(
                                        trackIndex = it,
                                        startFrame = recordStartFrame,
                                        lengthFrames = (engineState.framesRecorded - kPreRollFrames).coerceAtLeast(0).toLong(),
                                        peaks = livePeaks.toList(),
                                        framesPerPeak = (kSampleRate * kPollMs / 1000).toInt(),
                                    )
                                }
                            } else {
                                null
                            },
                            selected = selectedClip,
                            onSelect = { selectedClip = it },
                            snapEnabled = snapOn,
                            editable = editsEnabled,
                            onMoveClip = { from, toTrack, moved ->
                                val next = project.moveClip(engine, from.track, from.clip, toTrack, moved)
                                commit(next)
                                selectedClip = next.findClip(toTrack, moved)
                            },
                            onTrimClip = { ref, trimmed ->
                                val next = project.withClipTransform(engine, ref.track, ref.clip) { trimmed }
                                commit(next)
                                selectedClip = next.findClip(ref.track, trimmed)
                            },
                            onMoveTrack = { from, to ->
                                commit(project.moveTrack(from, to))
                                if (armedTrack == from) {
                                    armedTrack = to
                                } else if (armedTrack != null) {
                                    // Shift the armed index along with the reorder.
                                    val a = armedTrack!!
                                    armedTrack = when {
                                        from < a && to >= a -> a - 1
                                        from > a && to <= a -> a + 1
                                        else -> a
                                    }
                                }
                                selectedClip = null
                            },
                            onAddTrack = {
                                commit(project.addTrack())
                                if (armedTrack == null) armedTrack = project.tracks.lastIndex
                            },
                            headerWidth = if (landscape) 176.dp else 112.dp,
                            laneHeight = if (landscape) 84.dp else 72.dp,
                            trackHeader = { index, track ->
                                TrackHeader(
                                    index = index,
                                    track = track,
                                    compact = !landscape,
                                    armed = armedTrack == index,
                                    enabled = editsEnabled,
                                    showMeter = isRecording && armedTrack == index,
                                    meterPeakMilli = engineState.inputPeakMilli,
                                    meterClipped = engineState.inputClipped,
                                    onArm = { armedTrack = index },
                                    onMuteToggle = { commit(project.withTrackMuted(index, !track.muted)) },
                                    onSoloToggle = { commit(project.withTrackSoloed(index, !track.soloed)) },
                                    onGainChange = { commit(project.withTrackGain(index, it)) },
                                    onRename = { commit(project.renameTrack(index, it)) },
                                    onDelete = {
                                        commit(project.removeTrack(index))
                                        selectedClip = null
                                        armedTrack = when {
                                            project.tracks.isEmpty() -> null
                                            armedTrack == index -> index.coerceAtMost(project.tracks.lastIndex)
                                            armedTrack != null && armedTrack!! > index -> armedTrack!! - 1
                                            else -> armedTrack
                                        }
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (currentInputRoute?.isBluetooth == true && !bluetoothNoticeDismissed) {
                            BluetoothNotice(
                                onCalibrate = { showCalibration = true },
                                onDismiss = { bluetoothNoticeDismissed = true },
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
                            )
                        }

                        val selection = selectedClip
                        if (selection != null && editsEnabled) {
                            val clip = project.tracks.getOrNull(selection.track)?.clips?.getOrNull(selection.clip)
                            if (clip != null) {
                                ClipActionBar(
                                    canSplit = insertFrame > clip.startFrame && insertFrame < clip.startFrame + clip.lengthFrames,
                                    onSplit = {
                                        commit(project.splitClip(selection.track, selection.clip, insertFrame))
                                        selectedClip = null
                                    },
                                    onDuplicate = {
                                        val next = project.duplicateClip(engine, selection.track, selection.clip)
                                        commit(next)
                                        selectedClip = next.findClip(selection.track, clip.copy(startFrame = clip.startFrame + clip.lengthFrames))
                                    },
                                    onDelete = {
                                        commit(project.deleteClip(selection.track, selection.clip))
                                        selectedClip = null
                                    },
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                                )
                            }
                        }

                        statusMessage?.let {
                            StatusPill(
                                message = it,
                                onDismiss = { statusMessage = null },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = if (selectedClip != null) 64.dp else 12.dp, start = 16.dp, end = 16.dp),
                            )
                        }
                    }
                }
            }

            if (isRecording && usingBuiltinMic && micNatural != null) {
                val configuration = LocalConfiguration.current
                val rotation = remember(configuration) { context.display?.rotation ?: android.view.Surface.ROTATION_0 }
                MicPointer(rotateNaturalFraction(micNatural, rotation), Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            }

            if (tempoDialogOpen) {
                TempoDialog(
                    bpm = project.bpm,
                    onDismiss = { tempoDialogOpen = false },
                    onSet = { commit(project.copy(bpm = it)); tempoDialogOpen = false },
                )
            }
            if (showCalibration) {
                CalibrationWizardScreen(engine = engine, onDone = { showCalibration = false })
            }
        } else if (isRecording) {
            MinimizedRecordingBubble(
                elapsedText = if (recordedSeconds >= kMaxTakeSeconds - kTakeLimitWarnSeconds) {
                    "${formatElapsed(recordedSeconds)} / ${formatElapsed(kMaxTakeSeconds)}"
                } else {
                    formatElapsed(recordedSeconds)
                },
                onClick = onExpand,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            )
        } else if (projectLoaded && project.totalFrames > 0L && !WindowInsets.isImeVisible) {
            // The song's demo, playable from the lyrics. Kept clear of the
            // editor's autoscroll button (bottom end) and hidden while typing.
            val total = project.totalFrames
            val at = if (isPlaying) engineState.playbackFrame.toLong() else 0L
            DemoMiniPlayer(
                elapsedSeconds = (at / kSampleRate).toInt(),
                totalSeconds = ((total + kSampleRate - 1) / kSampleRate).toInt(),
                progress = at.toFloat() / total,
                isPlaying = isPlaying,
                onPlayToggle = {
                    if (isPlaying) {
                        engine.stopPlayback()
                        isPlaying = false
                    } else {
                        isPlaying = project.play(engine, 0L)
                    }
                },
                onOpen = onExpand,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 88.dp, bottom = 16.dp),
            )
        }
    }
}

/** Where a clip that was just committed now sits (punch-in re-sorts a track's clips). */
private fun MultitrackProject.findClip(track: Int, clip: MultitrackClipSpec): ClipRef? {
    val index = tracks.getOrNull(track)?.clips?.indexOfFirst { it.buffer === clip.buffer && it.startFrame == clip.startFrame } ?: -1
    return if (index >= 0) ClipRef(track, index) else null
}

/** Ableton-style position: bar.beat.sixteenth, 1-based. */
private fun formatPosition(frame: Long, framesPerBeat: Double, beatsPerBar: Int): String {
    val beats = frame / framesPerBeat
    val bar = floor(beats / beatsPerBar).toLong() + 1
    val beat = floor(beats % beatsPerBar).toInt() + 1
    val sixteenth = floor((beats - floor(beats)) * 4).toInt() + 1
    return "$bar.$beat.$sixteenth"
}

private fun formatClock(frame: Long): String {
    val tenths = frame * 10 / kSampleRate
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Bluetooth adds latency calibration can only partly correct; said where people will actually read it. */
@Composable
private fun BluetoothNotice(onCalibrate: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(modifier = Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Bluetooth audio lags. Wired headphones stay in time best.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            TextButton(onClick = onCalibrate) { Text("Calibrate") }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
            }
        }
    }
}
