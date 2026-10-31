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
import com.songnotes.core.audio.MultitrackTrackSpec
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

/** The engine state poll interval. */
private const val kPollMs = 100L

/** One live-waveform peak of a take being recorded: 50 ms, read from the take file as it's written. */
private const val kLivePeakFrames = kSampleRate / 20

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
    /** False while something covers the lyrics (a chord's panel, the piano): the lyrics bar hides unless a take is rolling. */
    lyricsBarAllowed: Boolean = true,
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
    // Where a seek drag is during playback, shown until it's released.
    var scrubFrame by remember { mutableStateOf<Long?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var engineState by remember { mutableStateOf(EngineState.idle()) }
    var snapOn by remember { mutableStateOf(true) }
    var tempoDialogOpen by remember { mutableStateOf(false) }
    val tutorialTargets = remember { TutorialTargets() }
    // The tour on screen, if any, and one queued to start once a mode switch has laid out.
    var tutorialMode by remember { mutableStateOf<ScratchpadMode?>(null) }
    var pendingTutorial by remember { mutableStateOf<ScratchpadMode?>(null) }
    val livePeaks = remember { mutableStateListOf<Float>() }
    // Reads the take's real audio as it's written, for the live waveform.
    var liveTakeReader by remember { mutableStateOf<LiveTakePeaks?>(null) }
    val scope = rememberCoroutineScope()
    val storage = remember(songId) { MultitrackProjectStorage(context, songId) }
    val prefs = remember { ScratchpadPreferences(context) }
    var mode by remember { mutableStateOf(prefs.mode) }
    // Where the take in progress is going: a track index, and whether that
    // track is only created once the take exists (a first voice note).
    var recordingTrack by remember { mutableStateOf(0) }
    var recordingCreatesTrack by remember { mutableStateOf(false) }
    // Set by each Record press, read once permissions are through: the
    // song's voice note (Track 1; Simple mode and the lyrics bar) or
    // Advanced's armed track.
    var recordVoiceNote by remember { mutableStateOf(false) }
    // The track Simple mode and the lyrics bar show, play and record onto:
    // Track 1 unless another is picked from the menu by its name; null once
    // it's deleted, until one is picked or a new take starts a new track.
    var simpleTrackPick by remember { mutableStateOf<Int?>(0) }
    // What Delete took away, so Undo can bring the view back to it.
    var deletedSimpleTrack by remember { mutableStateOf<Int?>(null) }
    // Simple mode and the lyrics bar hear the voice note alone unless this
    // is on (the chip under the note); Advanced always hears every track.
    var hearAllTracks by remember { mutableStateOf(prefs.hearAllTracks) }
    val simpleTrack: Int? = simpleTrackPick?.takeIf { it < project.tracks.size }
    // Which of the project's tracks are loaded in the engine right now
    // (what's playing, or a take's backing tracks), so live mute/solo/volume
    // changes go to the same list.
    var engineTracks by remember { mutableStateOf<List<Int>>(emptyList()) }
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
            // The playhead starts at the end of Track 1, where a take carries on.
            insertFrame = loaded.tracks.firstOrNull()?.clips?.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
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

    // While the Scratchpad is on screen it turns with the phone, respecting
    // the system auto-rotate lock (USER, which unlike FULL_USER leaves out
    // upside-down portrait); every other screen stays portrait. The
    // manifest's configChanges keep rotation from recreating the activity,
    // which owns the audio engine.
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(visible) {
        activity?.requestedOrientation =
            if (visible) ActivityInfo.SCREEN_ORIENTATION_USER else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
        // Undoing a Delete in Simple shows the track again.
        val deleted = deletedSimpleTrack
        if (simpleTrackPick == null && deleted != null && deleted < project.tracks.size) {
            simpleTrackPick = deleted
            deletedSimpleTrack = null
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
        // Simple mode and the lyrics bar record onto their picked track
        // (Track 1 by default); with none picked, the take starts a new one.
        // Advanced: onto the armed track, like Ableton.
        val voiceNote = recordVoiceNote
        if (!voiceNote && project.tracks.isEmpty()) return
        if (project.bpm <= 0.0) {
            statusMessage = "Set a tempo before recording."
            return
        }
        val createsTrack = voiceNote && simpleTrack == null
        val armProject = if (createsTrack) project.withNewVoiceTrack() else project
        val targetIndex = if (voiceNote) simpleTrack ?: armProject.tracks.lastIndex else (armedTrack ?: 0).coerceAtMost(project.tracks.lastIndex)
        if (!voiceNote) armedTrack = targetIndex
        selectedClip = null
        // Simple mode and the lyrics bar record from the playhead, which
        // waits at the end of the track unless moved (so by default a take
        // carries straight on); moved back, the take records over what it
        // covers. Never past the end, so a take can't leave a gap. Advanced
        // records at the insert marker.
        val startFrame = if (voiceNote) {
            val trackEnd = armProject.tracks[targetIndex].clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
            insertFrame.coerceIn(0L, trackEnd)
        } else {
            insertFrame
        }

        val route = AudioRouteDetector(context).currentInputRoute()
        val calibrationOffsetFrames = CalibrationStore(context).load(route.routeKey)?.offsetFrames ?: 0.0

        RecordingForegroundService.start(context, songTitle)
        // A voice note heard alone records without backing tracks too.
        val alone = voiceNote && !hearAllTracks
        val armSource = if (alone) armProject.copy(tracks = listOf(armProject.tracks[targetIndex])) else armProject
        val armed = armSource.armOverdub(
            engine, takeFile.absolutePath,
            targetIndex = if (alone) 0 else targetIndex, backingTracksStartFrame = startFrame,
            calibrationOffsetFrames = calibrationOffsetFrames,
        )
        engineTracks = if (alone) emptyList() else project.tracks.indices.filter { it != targetIndex }
        if (!armed) {
            RecordingForegroundService.stop(context)
            statusMessage = "Couldn't start recording — see Diagnostics for the last error."
            return
        }
        recordStartFrame = startFrame
        recordingTrack = targetIndex
        recordingCreatesTrack = createsTrack
        livePeaks.clear()
        liveTakeReader = LiveTakePeaks(takeFile, kLivePeakFrames)
        isRecording = true
        statusMessage = null
    }

    fun stopRecordingAndCommit(hitTakeLimit: Boolean = false) {
        engine.stopRecording()
        RecordingForegroundService.stop(context)
        val createsTrack = recordingCreatesTrack
        val targetIndex = recordingTrack
        val voiceNote = recordVoiceNote
        scope.launch {
            delay(150) // let the writer thread flush its last buffered frames to disk
            val takeSamples = withContext(Dispatchers.IO) { MultitrackProjectStorage.readF32(takeFile) }
            isRecording = false
            livePeaks.clear()
            if (takeSamples.isEmpty() || (!createsTrack && targetIndex > project.tracks.lastIndex)) {
                statusMessage = "Recording produced no audio."
                return@launch
            }
            val newClip = MultitrackClipSpec(buffer = takeSamples, startFrame = recordStartFrame)
            // A new track is only added once its take exists, so an empty or
            // failed take never leaves an empty track behind; one undo step
            // removes track and take together.
            val base = if (createsTrack && project.tracks.size == targetIndex) project.withNewVoiceTrack() else project
            commit(base.withPunchIn(engine, targetIndex, newClip))
            if (armedTrack == null) armedTrack = targetIndex
            if (voiceNote) {
                simpleTrackPick = targetIndex
                // The playhead waits at the end of the take, where the next one goes.
                insertFrame = recordStartFrame + takeSamples.size
            }
            val name = project.tracks[targetIndex].name
            statusMessage = if (hitTakeLimit) {
                "Reached the ${kMaxTakeSeconds / 60}-minute take limit — take saved on $name."
            } else {
                "Recorded %.1fs on %s.".format(takeSamples.size / kSampleRate.toDouble(), name)
            }
        }
    }

    /** Track indices a view hears: the voice-note views (Simple, the lyrics bar) hear their picked track alone unless [hearAllTracks]. */
    fun heardTracks(voiceNoteView: Boolean): List<Int> =
        if (voiceNoteView && !hearAllTracks) listOfNotNull(simpleTrack) else project.tracks.indices.toList()

    fun heardEnd(voiceNoteView: Boolean): Long =
        heardTracks(voiceNoteView).maxOfOrNull { i -> project.tracks[i].clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L } ?: 0L

    fun startPlayback(from: Long, voiceNoteView: Boolean): Boolean {
        val heard = heardTracks(voiceNoteView)
        engineTracks = heard
        return engine.startMultitrackPlayback(heard.map { project.tracks[it] }, project.bpm, project.beatsPerBar, from)
    }

    fun togglePlayback(voiceNoteView: Boolean = mode == ScratchpadMode.SIMPLE) {
        if (isPlaying) {
            engine.stopPlayback()
            isPlaying = false
            return
        }
        val end = heardEnd(voiceNoteView)
        if (end == 0L) {
            statusMessage = "Nothing to play yet — record a take first."
            return
        }
        // Ableton plays from the insert marker; past the end, from the top.
        val from = if (insertFrame < end) insertFrame else 0L
        isPlaying = startPlayback(from, voiceNoteView)
        if (!isPlaying) statusMessage = "Playback failed to start — see Diagnostics for the last error."
    }

    fun setHearAllTracks(on: Boolean) {
        hearAllTracks = on
        prefs.hearAllTracks = on
        // Swap what's playing without losing the place.
        if (isPlaying) {
            val at = engineState.playbackFrame.toLong()
            engine.stopPlayback()
            isPlaying = at < heardEnd(true) && startPlayback(at, true)
        }
    }

    fun exportMixdown() {
        if (project.tracks.isEmpty() || project.totalFrames == 0L) {
            statusMessage = "Nothing to export yet — record a take first."
            return
        }
        // What you hear is what you get: Simple mode with play-along off
        // shares just its track; otherwise the whole demo.
        val heard = heardTracks(voiceNoteView = mode == ScratchpadMode.SIMPLE)
        val source = project.copy(tracks = heard.map { project.tracks[it] })
        if (source.totalFrames == 0L) {
            statusMessage = "Nothing to export yet — record a take first."
            return
        }
        isExporting = true
        val outFile = mixdownFile(context, songTitle)
        scope.launch {
            val ok = withContext(Dispatchers.Default) { source.exportToWav(engine, outFile.absolutePath, kSampleRate) }
            isExporting = false
            if (ok) shareMixdown(context, outFile, songTitle) else statusMessage = "Export failed."
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

    fun onRecordPressed(voiceNote: Boolean = mode == ScratchpadMode.SIMPLE) {
        if (!isRecording) recordVoiceNote = voiceNote
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
            if (isRecording) {
                val reader = liveTakeReader
                if (reader != null) livePeaks.addAll(withContext(Dispatchers.IO) { reader.readNew() })
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

    // Mute/solo/volume changes reach what's already playing (or the backing
    // tracks of a take) live, without restarting, sent for exactly the
    // tracks the engine has ([engineTracks]).
    val mixFlags = project.tracks.map { Triple(it.gain, it.muted, it.soloed) }
    LaunchedEffect(mixFlags, isPlaying, isRecording, engineTracks) {
        if (!isPlaying && !isRecording) return@LaunchedEffect
        engine.setMultitrackMix(engineTracks.mapNotNull { project.tracks.getOrNull(it) })
    }

    // Simple mode's seeker. While playing, dragging only previews (moving
    // the engine on every frame of a drag would stutter); letting go jumps
    // playback there, like a voice-notes app.
    fun seekSimple(frame: Long, done: Boolean) {
        if (isRecording) return
        if (!isPlaying) {
            insertFrame = frame
            return
        }
        if (!done) {
            scrubFrame = frame
            return
        }
        scrubFrame = null
        insertFrame = frame
        engine.stopPlayback()
        isPlaying = frame < heardEnd(true) && startPlayback(frame, true)
    }

    fun switchMode(to: ScratchpadMode) {
        if (to == mode) return
        mode = to
        prefs.mode = to
        selectedClip = null
        // The first time a mode is used, its tour follows once it has laid out.
        if (!prefs.tutorialSeen(to)) pendingTutorial = to
    }

    LaunchedEffect(pendingTutorial) {
        val next = pendingTutorial ?: return@LaunchedEffect
        delay(600)
        tutorialMode = next
        pendingTutorial = null
    }

    // Status lines fade on their own.
    LaunchedEffect(statusMessage) {
        if (statusMessage != null) {
            delay(4000)
            statusMessage = null
        }
    }

    val recordedSeconds = engineState.framesRecorded / kSampleRate
    // During a tour on a song with nothing recorded yet, the views show the
    // example demo instead, so "tap a clip" has a clip. Display only: it's
    // never committed, saved or played, and it's gone when the tour ends.
    val showingExample = tutorialMode != null && project.totalFrames == 0L
    val shownTracks = if (showingExample) TutorialExample.tracks else project.tracks
    // Where the built-in mic is (no permission needed); null if the phone
    // doesn't say. Shown as a pointer while a take is armed or rolling.
    val micNatural = remember { locateBuiltinMic(context) }
    val usingBuiltinMic = currentInputRoute.let { it == null || it.isBuiltinMic || it.isBluetooth } || forceBuiltinMic
    // "Where's the mic?" in the ⋮ menus: the glow shows for a few seconds on
    // request (it used to show on every take, over Simple's record button).
    var micHintShown by remember { mutableStateOf(false) }
    LaunchedEffect(micHintShown) {
        if (micHintShown) {
            delay(5000)
            micHintShown = false
        }
    }
    val showMic: (() -> Unit)? = if (micNatural == null) null else {
        {
            micHintShown = true
            statusMessage = if (usingBuiltinMic) {
                "Your phone's mic is where the red glow is."
            } else {
                "You're recording from a connected mic, not the phone's own."
            }
        }
    }

    val positionFrame = when {
        scrubFrame != null -> scrubFrame!!
        isPlaying -> engineState.playbackFrame.toLong()
        isRecording -> recordStartFrame + (engineState.framesRecorded - kPreRollFrames).coerceAtLeast(0)
        else -> insertFrame
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (visible) {
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val editsEnabled = !isRecording && !isPlaying
            val framesPerBeat = kSampleRate * 60.0 / project.bpm.coerceAtLeast(1.0)
            val liveTake = if (isRecording && !engineState.isArmed) {
                RecordingPreview(
                    trackIndex = recordingTrack,
                    startFrame = recordStartFrame,
                    lengthFrames = (engineState.framesRecorded - kPreRollFrames).coerceAtLeast(0).toLong(),
                    peaks = livePeaks.toList(),
                    framesPerPeak = kLivePeakFrames,
                )
            } else {
                null
            }
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                if (mode == ScratchpadMode.SIMPLE) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        SimpleScratchpad(
                            engine = engine,
                            tracks = shownTracks,
                            landscape = landscape,
                            isRecording = isRecording,
                            isArmed = engineState.isArmed && isRecording,
                            countInBeatsRemaining = engineState.countInBeatsRemaining,
                            isPlaying = isPlaying,
                            positionFrame = positionFrame,
                            timeCaption = if (isRecording && recordedSeconds >= kMaxTakeSeconds - kTakeLimitWarnSeconds) {
                                "Stops automatically in ${(kMaxTakeSeconds - recordedSeconds).coerceAtLeast(0)}s"
                            } else {
                                null
                            },
                            recording = liveTake,
                            meterPeakMilli = engineState.inputPeakMilli,
                            meterClipped = engineState.inputClipped,
                            metronomeOn = project.metronomeOn,
                            bpm = project.bpm,
                            countInBars = project.countInBars,
                            canUndo = editsEnabled && undoStack.isNotEmpty(),
                            canRedo = editsEnabled && redoStack.isNotEmpty(),
                            editsEnabled = editsEnabled,
                            isExporting = isExporting,
                            onBack = onDone,
                            onSeek = { frame, done -> seekSimple(frame, done) },
                            onRecord = { onRecordPressed() },
                            onPlay = { togglePlayback(voiceNoteView = true) },
                            hearAllTracks = hearAllTracks,
                            onHearAllTracksChange = ::setHearAllTracks,
                            onMetronomeToggle = { commit(project.copy(metronomeOn = !project.metronomeOn)) },
                            onTempoClick = { tempoDialogOpen = true },
                            onCountInChange = { commit(project.copy(countInBars = it)) },
                            onUndo = ::undo,
                            onRedo = ::redo,
                            selectedTrack = if (showingExample) 0 else simpleTrack,
                            onSelectTrack = { i ->
                                if (isPlaying) togglePlayback(voiceNoteView = true)
                                simpleTrackPick = i
                                // At its end, ready to carry on recording it.
                                insertFrame = project.tracks.getOrNull(i)?.clips?.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
                            },
                            onTrim = { start, end -> simpleTrack?.let { commit(project.trimTrack(it, start, end)) } },
                            onRenameTrack = { name -> simpleTrack?.let { commit(project.renameTrack(it, name)) } },
                            onDeleteTrack = {
                                simpleTrack?.let { i ->
                                    commit(project.removeTrack(i))
                                    deletedSimpleTrack = i
                                    simpleTrackPick = null
                                    insertFrame = 0L
                                    armedTrack = when {
                                        project.tracks.isEmpty() -> null
                                        armedTrack == i -> i.coerceAtMost(project.tracks.lastIndex)
                                        armedTrack != null && armedTrack!! > i -> armedTrack!! - 1
                                        else -> armedTrack
                                    }
                                }
                            },
                            onSwitchToAdvanced = { switchMode(ScratchpadMode.ADVANCED) },
                            onExport = ::exportMixdown,
                            onCalibrate = { showCalibration = true },
                            onShowMic = showMic,
                            onTutorial = { tutorialMode = ScratchpadMode.SIMPLE },
                            tutorialTargets = tutorialTargets,
                            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                        )
                        if (currentInputRoute?.isBluetooth == true && !bluetoothNoticeDismissed) {
                            BluetoothNotice(
                                onCalibrate = { showCalibration = true },
                                onDismiss = { bluetoothNoticeDismissed = true },
                                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 56.dp),
                            )
                        }
                        statusMessage?.let {
                            StatusPill(
                                message = it,
                                onDismiss = { statusMessage = null },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .windowInsetsPadding(WindowInsets.safeDrawing)
                                    .padding(bottom = if (landscape) 112.dp else 292.dp, start = 16.dp, end = 16.dp),
                            )
                        }
                    }
                } else {
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
                        onBack = onDone,
                        onTempoClick = { tempoDialogOpen = true },
                        onBeatsPerBarChange = { commit(project.copy(beatsPerBar = it)) },
                        onMetronomeToggle = { commit(project.copy(metronomeOn = !project.metronomeOn)) },
                        onCountInChange = { commit(project.copy(countInBars = it)) },
                        onSnapToggle = { snapOn = !snapOn },
                        onPlay = { togglePlayback() },
                        onRecord = { onRecordPressed() },
                        onUndo = ::undo,
                        onRedo = ::redo,
                        onExport = ::exportMixdown,
                        onSwitchToSimple = { switchMode(ScratchpadMode.SIMPLE) },
                        onCalibrate = { showCalibration = true },
                        onShowMic = showMic,
                        onTutorial = { tutorialMode = ScratchpadMode.ADVANCED },
                        tutorialTargets = tutorialTargets,
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        ArrangementView(
                            engine = engine,
                            tracks = shownTracks,
                            bpm = project.bpm,
                            beatsPerBar = project.beatsPerBar,
                            insertFrame = insertFrame,
                            onInsertFrameChange = { insertFrame = it },
                            playheadFrame = if (isPlaying) engineState.playbackFrame.toLong() else null,
                            recording = liveTake,
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
                                simpleTrackPick = simpleTrackPick?.let { movedIndex(it, from, to) }
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
                                    isVoiceNote = index == simpleTrack && !showingExample,
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
                                        simpleTrackPick = simpleTrackPick?.let { p ->
                                            when {
                                                p == index -> null
                                                p > index -> p - 1
                                                else -> p
                                            }
                                        }
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
                            tutorialTargets = tutorialTargets,
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
            }

            if (micHintShown && usingBuiltinMic && micNatural != null) {
                val configuration = LocalConfiguration.current
                val rotation = remember(configuration) { context.display?.rotation ?: android.view.Surface.ROTATION_0 }
                MicPointer(rotateNaturalFraction(micNatural, rotation))
            }

            // A replay queued from Settings (switching to its mode if needed),
            // else the current mode's tour the first time it's ever used. Waits
            // a beat so any rotation has settled and the spotlight targets
            // have their final positions.
            LaunchedEffect(Unit) {
                val requested = prefs.requestedTutorial
                when {
                    requested != null -> {
                        if (requested != mode) {
                            mode = requested
                            prefs.mode = requested
                        }
                        delay(700)
                        tutorialMode = requested
                    }
                    !prefs.tutorialSeen(mode) -> {
                        delay(700)
                        tutorialMode = mode
                    }
                }
            }
            tutorialMode?.let { tourMode ->
                ScratchpadTutorial(tourMode, tutorialTargets, onStep = { target ->
                    // Select the clip the trim step points at, so its tabs show.
                    selectedClip = if (target == "first_clip") {
                        shownTracks.indexOfFirst { it.clips.isNotEmpty() }.takeIf { it >= 0 }?.let { ClipRef(it, 0) }
                    } else {
                        null
                    }
                }, onFinish = { toAdvanced ->
                    selectedClip = null
                    prefs.setTutorialSeen(tourMode)
                    if (prefs.requestedTutorial == tourMode) prefs.requestedTutorial = null
                    tutorialMode = null
                    // "Try Advanced" at the end of the Simple tour: switch, and
                    // Advanced's own tour follows the first time.
                    if (toAdvanced) switchMode(ScratchpadMode.ADVANCED)
                })
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
        } else if (projectLoaded && (isRecording || (lyricsBarAllowed && !WindowInsets.isImeVisible))) {
            // The voice note at the foot of the lyrics. Hidden while typing
            // or while a panel covers the lyrics, but never mid-take.
            LyricsRecorderBar(
                isRecording = isRecording,
                isArmed = isRecording && engineState.isArmed,
                countInBeatsRemaining = engineState.countInBeatsRemaining,
                countInBeatsTotal = project.countInBars * project.beatsPerBar,
                isPlaying = isPlaying,
                positionFrame = positionFrame,
                totalFrames = heardEnd(true),
                sampleRate = kSampleRate,
                limitCaption = if (isRecording && recordedSeconds >= kMaxTakeSeconds - kTakeLimitWarnSeconds) {
                    "stops in ${(kMaxTakeSeconds - recordedSeconds).coerceAtLeast(0)}s"
                } else {
                    null
                },
                meterPeakMilli = engineState.inputPeakMilli,
                meterClipped = engineState.inputClipped,
                metronomeOn = project.metronomeOn,
                countInBars = project.countInBars,
                onRecord = { onRecordPressed(voiceNote = true) },
                onPlayToggle = { togglePlayback(voiceNoteView = true) },
                onSeek = { frame, done -> seekSimple(frame, done) },
                onMetronomeToggle = { commit(project.copy(metronomeOn = !project.metronomeOn)) },
                onCountInChange = { commit(project.copy(countInBars = it)) },
                onOpen = onExpand,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
                    .fillMaxWidth(),
            )
            statusMessage?.let {
                StatusPill(
                    message = it,
                    onDismiss = { statusMessage = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 84.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
    }
}

/** Where a clip that was just committed now sits (punch-in re-sorts a track's clips). */
private fun MultitrackProject.findClip(track: Int, clip: MultitrackClipSpec): ClipRef? {
    val index = tracks.getOrNull(track)?.clips?.indexOfFirst { it.buffer === clip.buffer && it.startFrame == clip.startFrame } ?: -1
    return if (index >= 0) ClipRef(track, index) else null
}

/**
 * A new track at the end for a take Simple mode or the lyrics bar starts
 * with no track picked: "Voice note" (Track 1 of a new song), or "Voice
 * note 2" and so on if that's taken, in the next unused colour.
 */
private fun MultitrackProject.withNewVoiceTrack(): MultitrackProject {
    val taken = tracks.map { it.name }.toSet()
    val name = generateSequence(1) { it + 1 }.map { if (it == 1) "Voice note" else "Voice note $it" }.first { it !in taken }
    val color = (tracks.maxOfOrNull { it.colorIndex } ?: -1) + 1
    return addTrack(MultitrackTrackSpec(clips = emptyList(), name = name, colorIndex = color))
}

/** Where the track at [index] ends up when the track at [from] moves to [to]. */
private fun movedIndex(index: Int, from: Int, to: Int): Int = when {
    index == from -> to
    from < index && to >= index -> index - 1
    from > index && to <= index -> index + 1
    else -> index
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
            // Weighted, or on a narrow screen the text took the whole row and
            // squeezed Calibrate/✕ into a column of single letters.
            Text(
                "Bluetooth audio lags. Wired headphones stay in time best.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(onClick = onCalibrate) { Text("Calibrate") }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
            }
        }
    }
}
