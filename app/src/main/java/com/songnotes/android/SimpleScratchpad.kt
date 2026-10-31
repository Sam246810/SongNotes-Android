package com.songnotes.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.MultitrackTrackSpec

private const val kSampleRate = 48000

/** Shortest timeline Simple mode shows; the voice note otherwise fills the width, like a voice recorder. */
private const val kMinTimelineFrames = 1L * kSampleRate

/**
 * Ruled notebook paper behind Simple mode: faint horizontal rules and a
 * margin line, in the editor's own paper-line colour, so the Scratchpad
 * reads as part of the same notebook as the lyrics.
 */
fun Modifier.notebookPaper(
    rule: Color,
    margin: Color,
    spacing: Dp = RuleSpacing,
    marginInset: Dp = 22.dp,
    /** Where one rule falls, in px from the top; the rest follow every [spacing]. Null starts one spacing down. */
    anchorPx: Float? = null,
): Modifier =
    drawBehind {
        val step = spacing.toPx()
        var y = anchorPx?.let { ((it % step) + step) % step }?.takeIf { it > 0f } ?: step
        while (y < size.height) {
            drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            y += step
        }
        val x = marginInset.toPx()
        drawLine(margin, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
    }

/** The ruled lines' spacing; Simple mode's text sits on them. */
private val RuleSpacing = 34.dp

/** How far text sits above its rule, the way handwriting clears the line rather than touching it. */
private val RuleLift = 4.dp

/**
 * Ends this text's layout at its baseline (descenders still draw, below the
 * bounds), so the next line can be placed by baseline and a rule can be
 * lined up with the bottom edge.
 */
private fun Modifier.endAtBaseline(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val baseline = placeable[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: placeable.height
    layout(placeable.width, baseline, mapOf(FirstBaseline to baseline, LastBaseline to baseline)) { placeable.place(0, 0) }
}

/**
 * Simple mode: a voice recorder with a metronome, for the song's voice note
 * (Track 1). Every take goes on the end of it, Samsung Voice Recorder style;
 * there are no tracks, bars, beats or grid. The note shows as one big
 * waveform on ruled paper; tap or drag it to move the playhead, which is
 * where play starts. Tracks made in Advanced play along and show as a chip.
 *
 * It edits the same project as Advanced mode, so switching never loses
 * anything (a song's demo is one thing, shown two ways).
 */
@Composable
fun SimpleScratchpad(
    engine: AudioEngine,
    tracks: List<MultitrackTrackSpec>,
    landscape: Boolean,
    isRecording: Boolean,
    isArmed: Boolean,
    countInBeatsRemaining: Int,
    isPlaying: Boolean,
    positionFrame: Long,
    timeCaption: String?,
    recording: RecordingPreview?,
    meterPeakMilli: Int,
    meterClipped: Boolean,
    metronomeOn: Boolean,
    bpm: Double,
    countInBars: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    editsEnabled: Boolean,
    isExporting: Boolean,
    onBack: () -> Unit,
    /** [done] is false while a drag is still moving, true on tap or release. */
    onSeek: (frame: Long, done: Boolean) -> Unit,
    onRecord: () -> Unit,
    onPlay: () -> Unit,
    onMetronomeToggle: () -> Unit,
    onTempoClick: () -> Unit,
    onCountInChange: (Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    /** The track shown, played and recorded onto (Track 1 unless another was picked); null when none is. */
    selectedTrack: Int?,
    onSelectTrack: (Int) -> Unit,
    /** Trims the shown track to its kept window. */
    onTrim: (startFrame: Long, endFrame: Long) -> Unit,
    /** Renames the shown track (the same name Advanced shows). */
    onRenameTrack: (String) -> Unit,
    /** Deletes the shown track; Simple then shows no track until one is picked or recorded. */
    onDeleteTrack: () -> Unit,
    /** Whether the other tracks play along (the chip under the note); off plays the voice note alone. */
    hearAllTracks: Boolean,
    onHearAllTracksChange: (Boolean) -> Unit,
    onSwitchToAdvanced: () -> Unit,
    onExport: () -> Unit,
    onCalibrate: () -> Unit,
    /** Shows where the phone's mic is for a few seconds; null when the phone doesn't say. */
    onShowMic: (() -> Unit)?,
    onTutorial: () -> Unit,
    tutorialTargets: TutorialTargets?,
    modifier: Modifier = Modifier,
) {
    // The timeline is what you hear: the voice note, or the whole demo when
    // the other tracks play along.
    val note = selectedTrack?.let { tracks.getOrNull(it) }
    val noteEnd = note?.clips?.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
    val projectEnd = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L } ?: 0L
    val heardEnd = if (hearAllTracks) projectEnd else noteEnd
    val recordingEnd = recording?.let { it.startFrame + it.lengthFrames } ?: 0L
    val timelineFrames = maxOf(heardEnd, recordingEnd, kMinTimelineFrames)
    val paper = SongNotesTheme.colors
    val marginColor = Color(0xFFD27D6B).copy(alpha = 0.28f)
    // The rules are phased so one runs exactly under the note's heading
    // (wherever centring puts it), and the text sits on the lines like writing.
    var paperTop by remember { mutableFloatStateOf(0f) }
    var headingBaseline by remember { mutableStateOf<Float?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(paper.parchment)
            .onGloballyPositioned { paperTop = it.positionInWindow().y }
            .notebookPaper(paper.paperLine, marginColor, anchorPx = headingBaseline?.let { it - paperTop }),
    ) {
        SimpleTopBar(
            isRecording = isRecording,
            canUndo = canUndo,
            canRedo = canRedo,
            editsEnabled = editsEnabled,
            isExporting = isExporting,
            onBack = onBack,
            onUndo = onUndo,
            onRedo = onRedo,
            onSwitchToAdvanced = onSwitchToAdvanced,
            onExport = onExport,
            onCalibrate = onCalibrate,
            onShowMic = onShowMic,
            onTutorial = onTutorial,
        )

        val layers: @Composable (Modifier) -> Unit = { m ->
            VoiceNotePanel(
                engine = engine,
                tracks = tracks,
                selectedTrack = selectedTrack,
                onSelectTrack = onSelectTrack,
                otherTracks = tracks.filterIndexed { i, t -> i != selectedTrack && t.clips.isNotEmpty() }.size,
                timelineFrames = timelineFrames,
                positionFrame = positionFrame,
                recording = recording,
                editsEnabled = editsEnabled,
                onSeek = onSeek,
                onTrim = onTrim,
                onDelete = onDeleteTrack,
                onRename = onRenameTrack,
                hearAllTracks = hearAllTracks,
                onHearAllTracksChange = onHearAllTracksChange,
                onHeadingBaseline = { headingBaseline = it },
                tutorialTargets = tutorialTargets,
                modifier = m,
            )
        }
        val transport: @Composable (Modifier) -> Unit = { m ->
            SimpleTransport(
                wide = landscape,
                isRecording = isRecording,
                isArmed = isArmed,
                countInBeatsRemaining = countInBeatsRemaining,
                isPlaying = isPlaying,
                canPlay = heardEnd > 0L,
                positionFrame = positionFrame,
                timelineFrames = timelineFrames,
                onSeek = onSeek,
                timeCaption = timeCaption,
                hasNote = note?.clips?.isNotEmpty() == true,
                recordsOver = noteEnd > 0L && positionFrame < noteEnd,
                meterPeakMilli = meterPeakMilli,
                meterClipped = meterClipped,
                metronomeOn = metronomeOn,
                bpm = bpm,
                countInBars = countInBars,
                editsEnabled = editsEnabled,
                onRecord = onRecord,
                onPlay = onPlay,
                onMetronomeToggle = onMetronomeToggle,
                onTempoClick = onTempoClick,
                onCountInChange = onCountInChange,
                tutorialTargets = tutorialTargets,
                modifier = m,
            )
        }
        // Controls along the bottom in both orientations; landscape packs
        // them into one bar.
        layers(Modifier.weight(1f).fillMaxWidth())
        transport(Modifier.fillMaxWidth())
    }
}

@Composable
private fun SimpleTopBar(
    isRecording: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    editsEnabled: Boolean,
    isExporting: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSwitchToAdvanced: () -> Unit,
    onExport: () -> Unit,
    onCalibrate: () -> Unit,
    onShowMic: (() -> Unit)?,
    onTutorial: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isRecording) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Lyrics")
            }
        } else {
            IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = "Back to lyrics") }
        }
        ModeSwitch(
            mode = ScratchpadMode.SIMPLE,
            enabled = !isRecording,
            onSelect = { onSwitchToAdvanced() },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        // Undo/redo can't be used mid-take, and hiding them leaves room for
        // the wider "← Lyrics" so the ⋮ menu isn't pushed off a narrow screen.
        if (!isRecording) {
            IconButton(enabled = canUndo, onClick = onUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
            IconButton(enabled = canRedo, onClick = onRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (isExporting) "Exporting…" else "Share as WAV") },
                    leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                    enabled = editsEnabled && !isExporting,
                    onClick = { menuOpen = false; onExport() },
                )
                DropdownMenuItem(
                    text = { Text("Calibrate recording latency") },
                    leadingIcon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                    enabled = editsEnabled,
                    onClick = { menuOpen = false; onCalibrate() },
                )
                if (onShowMic != null) {
                    DropdownMenuItem(
                        text = { Text("Where's the mic?") },
                        leadingIcon = { Icon(Icons.Filled.Mic, contentDescription = null) },
                        onClick = { menuOpen = false; onShowMic() },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Tutorial") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                    onClick = { menuOpen = false; onTutorial() },
                )
            }
        }
    }
}

/**
 * The picked track (Track 1 unless another was chosen from the ▾ next to its
 * name) as one big waveform, Samsung Voice Recorder style: the whole note to
 * tap or drag through while stopped, and while recording, the take
 * scrolling in to a fixed centre line. Its name renames it; Trim and Delete
 * sit beside it. The other tracks only play along (the chip), so this is
 * multitracking without the grid: pick a track, hear it alone or with the
 * rest.
 */
@Composable
private fun VoiceNotePanel(
    engine: AudioEngine,
    tracks: List<MultitrackTrackSpec>,
    selectedTrack: Int?,
    onSelectTrack: (Int) -> Unit,
    otherTracks: Int,
    timelineFrames: Long,
    positionFrame: Long,
    recording: RecordingPreview?,
    editsEnabled: Boolean,
    onSeek: (Long, Boolean) -> Unit,
    onTrim: (startFrame: Long, endFrame: Long) -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    hearAllTracks: Boolean,
    onHearAllTracksChange: (Boolean) -> Unit,
    /** The heading's baseline, in window px, for the page to line its rules up with. */
    onHeadingBaseline: (Float) -> Unit,
    tutorialTargets: TutorialTargets?,
    modifier: Modifier,
) {
    val note = selectedTrack?.let { tracks.getOrNull(it) }
    val noteStart = note?.clips?.minOfOrNull { it.startFrame } ?: 0L
    val noteEnd = note?.clips?.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
    val canPick = editsEnabled && recording == null
    if (note == null && recording == null) {
        // A new song, or the picked track was deleted.
        Column(modifier = modifier) {
            if (tracks.isEmpty()) {
                EmptyDemo(
                    title = "Hum it before it's gone",
                    body = "Tap the red button to record a voice note. Record again later to carry on from the playhead.",
                    onHeadingBaseline = onHeadingBaseline,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                EmptyDemo(
                    title = "No track picked",
                    body = "Choose one of this song's tracks, or tap the red button to start a new one.",
                    onHeadingBaseline = onHeadingBaseline,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    TrackPicker(tracks, selectedTrack, canPick, onSelectTrack) { open ->
                        OutlinedButton(enabled = canPick, onClick = open) {
                            Text("Choose a track")
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                    }
                }
            }
            if (otherTracks > 0) {
                OtherTracksToggle(otherTracks, hearAllTracks, onHearAllTracksChange, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp))
            }
        }
        return
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val liftPx = with(LocalDensity.current) { RuleLift.toPx() }
    // The track's own name, so Simple and Advanced call it the same thing.
    val noteName = note?.name?.takeIf { it.isNotBlank() } ?: "Voice note"
    var trim by remember(note?.clips) { mutableStateOf<Pair<Long, Long>?>(null) }

    // The note, its actions, the trim row and the chip sit together as one
    // block, centred on the page: even side gutters (the left one clears
    // the margin line) and the block in the middle of the free height,
    // rather than the header pinned to the top and a gap under it.
    Column(
        modifier = modifier.padding(horizontal = 36.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // Trim/Delete line up with the heading's baseline, on its rule.
        Row {
            Column(modifier = Modifier.weight(1f).alignByBaseline()) {
                // Written on the rules: the heading's layout ends at its
                // baseline (reported to the page, which puts a rule just
                // under it), and the length's baseline sits one rule below.
                // Tap the name to rename the track; ▾ picks another one.
                Row(verticalAlignment = Alignment.Bottom) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false).clickable(enabled = canPick) { renaming = true },
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            noteName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                // Outside endAtBaseline, so it measures the box that ends at the baseline.
                                .onGloballyPositioned { onHeadingBaseline(it.positionInWindow().y + it.size.height + liftPx) }
                                .endAtBaseline(),
                        )
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Rename",
                            tint = SongNotesTheme.colors.muted,
                            modifier = Modifier.padding(start = 6.dp, bottom = 1.dp).size(14.dp),
                        )
                    }
                    if (tracks.size > 1) {
                        TrackPicker(tracks, selectedTrack, canPick, onSelectTrack) { open ->
                            Icon(
                                Icons.Filled.ArrowDropDown,
                                contentDescription = "Choose a track",
                                tint = if (canPick) MaterialTheme.colorScheme.primary else SongNotesTheme.colors.muted,
                                modifier = Modifier
                                    // The glyph sits mid-box; this brings it down beside the text.
                                    .offset(y = 6.dp)
                                    .padding(start = 4.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = canPick, onClick = open)
                                    .size(24.dp),
                            )
                        }
                    }
                }
                Text(
                    when {
                        recording != null -> "Recording…"
                        noteEnd == 0L -> "Empty"
                        else -> formatElapsed(((noteEnd + kSampleRate - 1) / kSampleRate).toInt())
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (recording != null) RecordRed else SongNotesTheme.colors.muted,
                    modifier = Modifier.paddingFromBaseline(top = RuleSpacing, bottom = 8.dp),
                )
            }
            // Out in the open rather than behind a ⋮: there's room, and a
            // menu on the note next to the top bar's own ⋮ was confusing.
            // Hidden while trimming, where the row under the note has the
            // trim's own Cancel/Trim.
            if (trim == null) {
                Row(
                    modifier = Modifier.alignByBaseline().tutorialTarget(tutorialTargets, "voice_note_actions"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = canPick && noteEnd > noteStart, onClick = { trim = noteStart to noteEnd }) {
                        Icon(Icons.Filled.ContentCut, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Trim")
                    }
                    TextButton(
                        enabled = canPick,
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // A capped waveform: on a tall screen a full-height one was mostly
        // empty space around a thin line. Unfilled weight, so on a short
        // (landscape) screen it shrinks to fit and the block stays centred.
        Box(modifier = Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
            val waveModifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).fillMaxHeight()
            when {
                recording != null -> LiveRecordingWave(engine, recording, note, waveModifier)
                note != null && noteEnd == 0L -> Text(
                    "Nothing on this track yet. Tap the red button to record.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SongNotesTheme.colors.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                note != null -> VoiceNoteWave(
                    engine = engine,
                    note = note,
                    timelineFrames = timelineFrames,
                    positionFrame = positionFrame,
                    trim = trim,
                    noteStart = noteStart,
                    noteEnd = noteEnd,
                    onTrimChange = { trim = it },
                    onSeek = onSeek,
                    modifier = waveModifier.tutorialTarget(tutorialTargets, "voice_note_wave"),
                )
            }
        }
        trim?.let { (start, end) ->
            // What's kept and what's cut on one line, then the buttons; side
            // by side the times wrapped on a narrow screen.
            val cutFrames = (start - noteStart) + (noteEnd - end)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Keep ${formatClockFrames(start)} – ${formatClockFrames(end)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = SongNotesTheme.colors.muted,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (cutFrames > 0L) "Cuts %.1fs".format(cutFrames / kSampleRate.toDouble()) else "Drag a handle in to cut",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (cutFrames > 0L) MaterialTheme.colorScheme.error else SongNotesTheme.colors.muted,
                    maxLines = 1,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { trim = null }) { Text("Cancel") }
                Button(onClick = { onTrim(start, end); trim = null }) { Text("Trim") }
            }
        }
        if (otherTracks > 0) {
            Spacer(Modifier.height(12.dp))
            OtherTracksToggle(otherTracks, hearAllTracks, onHearAllTracksChange, Modifier.align(Alignment.CenterHorizontally))
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(noteName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename track") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(32) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { onRename(text.trim()); renaming = false }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete $noteName?") },
            text = { Text("The whole track goes. Undo brings it back.") },
            confirmButton = {
                TextButton(onClick = { onDelete(); confirmDelete = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The song's tracks to pick from, each with its length; the picked one is
 * ticked. [trigger] draws what opens it, given the function that opens it.
 */
@Composable
private fun TrackPicker(
    tracks: List<MultitrackTrackSpec>,
    selected: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    trigger: @Composable (open: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        trigger { if (enabled) open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tracks.forEachIndexed { i, track ->
                val end = track.clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
                DropdownMenuItem(
                    text = { Text(track.name.ifBlank { "Track ${i + 1}" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(trackColor(track.colorIndex)))
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (end > 0L) formatElapsed(((end + kSampleRate - 1) / kSampleRate).toInt()) else "Empty",
                                style = MaterialTheme.typography.labelMedium,
                                color = SongNotesTheme.colors.muted,
                            )
                            if (i == selected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Filled.Check, contentDescription = "Shown", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    onClick = { open = false; if (i != selected) onSelect(i) },
                )
            }
        }
    }
}

/**
 * The song's other tracks (made in Advanced), which Simple mode leaves out
 * unless this is on: "Play along with 4 tracks". On, they play under the
 * voice note and back a take too.
 */
@Composable
private fun OtherTracksToggle(count: Int, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val noun = if (count == 1) "track" else "tracks"
    Surface(
        modifier = modifier.toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        shape = RoundedCornerShape(50),
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, if (on) Color.Transparent else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (on) Icons.Filled.Headset else Icons.Filled.HeadsetOff, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (on) "Playing along with $count $noun" else "Play along with $count other $noun",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun EmptyDemo(
    title: String,
    body: String,
    onHeadingBaseline: (Float) -> Unit,
    modifier: Modifier,
    below: @Composable () -> Unit = {},
) {
    val liftPx = with(LocalDensity.current) { RuleLift.toPx() }
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(16.dp))
        // Written on the rules, like the note's heading: one line per rule.
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                // Outside endAtBaseline, so it measures the box that ends at the baseline.
                .onGloballyPositioned { onHeadingBaseline(it.positionInWindow().y + it.size.height + liftPx) }
                .endAtBaseline(),
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = with(LocalDensity.current) { RuleSpacing.toSp() }),
            color = SongNotesTheme.colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.paddingFromBaseline(top = RuleSpacing),
        )
        Spacer(Modifier.height(16.dp))
        below()
    }
}

/**
 * The voice note across the timeline, with the playhead to tap or drag. In
 * trim mode ([trim] non-null) it also shows two bracket handles with tabs
 * at the bottom, Samsung Voice Recorder style. A drag that starts on a
 * handle's tab (or its line, away from the playhead) moves the handle;
 * anywhere else moves the playhead, so you can listen from any point while
 * setting the cut. Past the note's end (when the other tracks play along),
 * the rest of the demo shows as a faint band.
 *
 * Pinch to zoom in (down to about half a second across the box) and drag
 * with two fingers to move along, for precise trims; a "2.0× · fit" badge
 * zooms back out, and a thin bar along the bottom shows where you are.
 * Dragging a handle against either edge while zoomed moves the view along.
 */
@Composable
private fun VoiceNoteWave(
    engine: AudioEngine,
    note: MultitrackTrackSpec,
    timelineFrames: Long,
    positionFrame: Long,
    trim: Pair<Long, Long>?,
    noteStart: Long,
    noteEnd: Long,
    onTrimChange: (Pair<Long, Long>) -> Unit,
    onSeek: (Long, Boolean) -> Unit,
    modifier: Modifier,
) {
    val color = trackColor(note.colorIndex)
    val pyramids = remember(note.clips) { note.clips.map { engine.buildPeakPyramid(it.buffer) } }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentTrim by rememberUpdatedState(trim)
    val currentPosition by rememberUpdatedState(positionFrame)
    val currentOnTrimChange by rememberUpdatedState(onTrimChange)
    val currentTimeline by rememberUpdatedState(timelineFrames)
    val currentNoteStart by rememberUpdatedState(noteStart)
    val currentNoteEnd by rememberUpdatedState(noteEnd)
    // The visible window: zoom 1 shows the whole timeline; viewStart is the
    // first frame on screen. Back to the whole note after any edit to it.
    // One pair of states for the composable's whole life, reset in place:
    // remembering them keyed on the clips made new ones after every take,
    // trim or undo, while the long-lived gesture loop kept the old pair, so
    // pinches and drags went to a view the screen no longer drew.
    var zoom by remember { mutableFloatStateOf(1f) }
    var viewStart by remember { mutableLongStateOf(0L) }
    LaunchedEffect(note.clips) {
        zoom = 1f
        viewStart = 0L
    }
    val maxZoom = (timelineFrames / (kSampleRate / 2f)).coerceAtLeast(1f)
    fun visibleFrames(): Long = (currentTimeline / zoom).toLong().coerceAtLeast(1L)
    fun clampView() {
        zoom = zoom.coerceIn(1f, (currentTimeline / (kSampleRate / 2f)).coerceAtLeast(1f))
        viewStart = viewStart.coerceIn(0L, (currentTimeline - visibleFrames()).coerceAtLeast(0L))
    }
    // The box's width in px, for the edge-scroll loop below (outside the gesture scope).
    var boxWidth by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current
    // A one-finger drag in progress: the finger's x (may be past either
    // edge) and what it moves (0/1 a trim handle, null the playhead).
    var dragX by remember { mutableStateOf<Float?>(null) }
    val dragHandle = remember { arrayOfNulls<Int>(1) }
    val lastSeek = remember { longArrayOf(0L) }
    val minTrimFrames = kSampleRate / 10L

    /** Moves whatever's being dragged to the frame under x (clamped into the box). */
    fun applyDrag(x: Float, handle: Int?) {
        val f = (viewStart + x.coerceIn(0f, boxWidth) / boxWidth * visibleFrames()).toLong().coerceIn(0L, currentTimeline)
        val window = currentTrim
        if (handle != null && window != null) {
            currentOnTrimChange(
                if (handle == 0) {
                    f.coerceIn(currentNoteStart, window.second - minTrimFrames) to window.second
                } else {
                    window.first to f.coerceIn(window.first + minTrimFrames, currentNoteEnd)
                },
            )
        } else {
            lastSeek[0] = f
            currentOnSeek(f, false)
        }
    }

    // Zoomed in, a drag held near or past either edge glides the view that
    // way every frame (faster the further in) with the playhead or handle
    // staying under the finger, like the Advanced ruler.
    LaunchedEffect(dragX != null) {
        if (dragX == null) return@LaunchedEffect
        val zone = with(density) { EdgeScrollZone.toPx() }
        val maxSpeed = with(density) { EdgeScrollMaxDpPerSecond.dp.toPx() }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            val x = dragX ?: break
            if (zoom <= 1f) continue
            val depth = when {
                x > boxWidth - zone -> ((x - (boxWidth - zone)) / zone).coerceAtMost(1f)
                x < zone -> -((zone - x) / zone).coerceAtMost(1f)
                else -> 0f
            }
            if (depth == 0f) continue
            val before = viewStart
            viewStart += (depth * maxSpeed * seconds * visibleFrames() / boxWidth).toLong()
            clampView()
            if (viewStart != before) applyDrag(x, dragHandle[0])
        }
    }

    // While zoomed in and playing, page along with the playhead.
    LaunchedEffect(positionFrame, zoom) {
        if (zoom > 1f && trim == null && dragX == null) {
            val visible = visibleFrames()
            if (positionFrame < viewStart || positionFrame > viewStart + visible) {
                viewStart = positionFrame - visible / 10
                clampView()
            }
        }
    }

    val waveColor = lerp(color, Color.Black, 0.25f)
    val playheadColor = MaterialTheme.colorScheme.primary
    val handleColor = MaterialTheme.colorScheme.secondary
    // What a trim will remove is marked in red, hatched and labelled "cut",
    // so there's no guessing which side of a handle goes (Samsung's plain
    // dimming left that unclear). The kept part looks just as it did.
    val cutColor = MaterialTheme.colorScheme.error
    val cutLabelStyle = MaterialTheme.typography.labelMedium.copy(color = cutColor, fontWeight = FontWeight.SemiBold)
    val cutLabelBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val textMeasurer = rememberTextMeasurer()
    val restColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val scrollTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val scrollThumb = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Box(modifier = modifier.onSizeChanged { boxWidth = it.width.toFloat().coerceAtLeast(1f) }) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(16.dp))
                .background(color.copy(alpha = 0.14f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                // One gesture loop for everything, so a second finger turns a
                // touch into a pinch instead of fighting a one-finger drag.
                .pointerInput(Unit) {
                    fun frameAt(x: Float): Long =
                        (viewStart + x / size.width * visibleFrames()).toLong().coerceIn(0L, currentTimeline)
                    fun xOf(frame: Long): Float = ((frame - viewStart).toDouble() / visibleFrames() * size.width).toFloat()
                    val reach = 16.dp.toPx()
                    val tabW = HandleTabWidth.toPx()
                    val tabSlop = 10.dp.toPx()
                    // Read at each touch, not once: the box gets shorter when
                    // trim mode opens (its row appears below).
                    fun tabY() = size.height - HandleTabHeight.toPx() - HandleTabInset.toPx()
                    // Which handle a touch at (x, y) picks up, if any. A handle's
                    // tab (bottom, on its inner side) always grabs it. Its line
                    // grabs it only within a fingertip, only for a drag, and only
                    // when the playhead isn't right there too, so the playhead
                    // is never stuck under a handle.
                    fun handleAt(x: Float, y: Float, isTap: Boolean): Int? {
                        val (a, b) = currentTrim ?: return null
                        val xa = xOf(a)
                        val xb = xOf(b)
                        if (y >= tabY() - tabSlop) {
                            if (x in (xa - tabSlop)..(xa + tabW + tabSlop)) return 0
                            if (x in (xb - tabW - tabSlop)..(xb + tabSlop)) return 1
                        }
                        if (isTap || kotlin.math.abs(x - xOf(currentPosition)) <= reach) return null
                        val da = kotlin.math.abs(x - xa)
                        val db = kotlin.math.abs(x - xb)
                        return when {
                            da <= reach && da <= db -> 0
                            db <= reach -> 1
                            else -> null
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val handle = handleAt(down.position.x, down.position.y, isTap = false)
                        var travel = Offset.Zero
                        var dragging = false
                        var pinching = false
                        lastSeek[0] = frameAt(down.position.x)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // Pinch: zoom about the fingers, and pan with them.
                                pinching = true
                                val centroid = event.calculateCentroid()
                                val anchor = frameAt(centroid.x)
                                zoom *= event.calculateZoom()
                                clampView()
                                viewStart = anchor - (centroid.x / size.width * visibleFrames()).toLong()
                                viewStart -= (event.calculatePan().x / size.width * visibleFrames()).toLong()
                                clampView()
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            // After a pinch, the finger left behind does nothing.
                            if (pinching) continue
                            val change = pressed.first()
                            if (!dragging) {
                                travel += change.positionChange()
                                if (kotlin.math.abs(travel.x) > viewConfiguration.touchSlop) dragging = true
                            }
                            if (!dragging) continue
                            change.consume()
                            val x = change.position.x
                            val target = if (currentTrim != null) handle else null
                            dragHandle[0] = target
                            dragX = x
                            applyDrag(x, target)
                        }
                        dragX = null
                        when {
                            pinching -> Unit
                            dragging -> if (handle == null || currentTrim == null) currentOnSeek(lastSeek[0], true)
                            // A tap moves the playhead, unless it was on a tab.
                            handleAt(down.position.x, down.position.y, isTap = true) == null ->
                                currentOnSeek(frameAt(down.position.x), true)
                        }
                    }
                },
        ) {
            val visible = visibleFrames()
            val framesPerPx = visible.toDouble() / size.width
            fun xOf(frame: Long): Float = ((frame - viewStart) / framesPerPx).toFloat()
            val inset = 12.dp.toPx()
            // The rest of the demo, when it runs on past the note.
            val noteX = xOf(noteEnd)
            if (noteX < size.width - 1f) {
                val from = noteX.coerceAtLeast(0f)
                drawRect(restColor, topLeft = Offset(from, 0f), size = Size(size.width - from, size.height))
            }
            note.clips.forEachIndexed { i, clip ->
                val x0 = xOf(clip.startFrame)
                val x1 = xOf(clip.startFrame + clip.lengthFrames)
                if (x1 < 0f || x0 > size.width) return@forEachIndexed
                // Start drawing at the left edge, not the clip's (possibly
                // far off-screen) start, so a zoomed view only draws what shows.
                val skipPx = (-x0).coerceAtLeast(0f)
                clipRect(left = x0.coerceAtLeast(0f), right = x1.coerceAtMost(size.width)) {
                    translate(left = x0 + skipPx) {
                        drawClipWaveform(
                            clip = clip,
                            pyramid = pyramids[i],
                            firstFrameInClip = (skipPx * framesPerPx).toLong(),
                            framesPerPx = framesPerPx,
                            top = inset,
                            height = size.height - 2 * inset,
                            color = waveColor,
                        )
                    }
                }
            }
            val window = trim
            if (window != null) {
                // Mark what will be cut (red wash, diagonal hatching over the
                // waveform, and a "✂ cut" label where it fits), then the two
                // brackets: a thin line with a rounded tab on its inner side, so
                // even a handle at the very start or end is fully on screen.
                val xs = xOf(window.first)
                val xe = xOf(window.second)
                val hatchGap = 9.dp.toPx()
                val label = textMeasurer.measure("✂ cut", cutLabelStyle)
                val labelPad = 6.dp.toPx()
                for ((l, r) in listOf(xOf(noteStart) to xs, xe to xOf(noteEnd))) {
                    val left = l.coerceAtLeast(0f)
                    val right = r.coerceAtMost(size.width)
                    if (right - left < 1f) continue
                    drawRect(cutColor.copy(alpha = 0.08f), topLeft = Offset(left, 0f), size = Size(right - left, size.height))
                    clipRect(left = left, right = right) {
                        var x = left - size.height
                        while (x < right) {
                            drawLine(cutColor.copy(alpha = 0.35f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.5.dp.toPx())
                            x += hatchGap
                        }
                    }
                    val lw = label.size.width + 2 * labelPad
                    val lh = label.size.height + labelPad
                    if (right - left >= lw + 8.dp.toPx()) {
                        val lx = (left + right - lw) / 2f
                        val ly = (size.height - lh) / 2f
                        drawRoundRect(cutLabelBg, topLeft = Offset(lx, ly), size = Size(lw, lh), cornerRadius = CornerRadius(lh / 2f))
                        drawText(label, topLeft = Offset(lx + labelPad, ly + labelPad / 2f))
                    }
                }
                val line = 2.5.dp.toPx()
                val tabW = HandleTabWidth.toPx()
                val tabH = HandleTabHeight.toPx()
                val tabY = size.height - tabH - HandleTabInset.toPx()
                val corner = CornerRadius(5.dp.toPx())
                val gripColor = Color.White.copy(alpha = 0.85f)
                for ((hx, inward) in listOf(xs to 1f, xe to -1f)) {
                    if (hx < -tabW || hx > size.width + tabW) continue
                    val x = if (inward > 0) hx.coerceAtLeast(0f) else hx.coerceAtMost(size.width)
                    val lineX = if (inward > 0) x else x - line
                    drawRect(handleColor, topLeft = Offset(lineX, 0f), size = Size(line, size.height))
                    val tabX = if (inward > 0) x else x - tabW
                    drawRoundRect(handleColor, topLeft = Offset(tabX, tabY), size = Size(tabW, tabH), cornerRadius = corner)
                    // Two little grip lines on the tab.
                    val gx = tabX + tabW / 2f
                    for (dx in listOf(-2.5.dp.toPx(), 2.5.dp.toPx())) {
                        drawLine(gripColor, Offset(gx + dx, tabY + tabH * 0.3f), Offset(gx + dx, tabY + tabH * 0.7f), strokeWidth = 1.5.dp.toPx())
                    }
                }
            }
            // The playhead, with a knob on top so it reads as something to grab.
            // Kept clear of the rounded corners, so it's visible at 0:00 and at
            // the end; not drawn when zoomed away from it.
            val r = 6.dp.toPx()
            val rawPx = xOf(positionFrame)
            if (rawPx >= -r && rawPx <= size.width + r) {
                val px = rawPx.coerceIn(r + 2f, size.width - r - 2f)
                drawLine(playheadColor, Offset(px, r), Offset(px, size.height), strokeWidth = 2.5.dp.toPx())
                drawCircle(playheadColor, radius = r, center = Offset(px, r + 2f))
            }
            // Zoomed in: where the view sits in the whole note.
            if (zoom > 1.01f) {
                val barY = size.height - 3.dp.toPx()
                val barX0 = 16.dp.toPx()
                val barW = size.width - 2 * barX0
                drawLine(scrollTrack, Offset(barX0, barY), Offset(barX0 + barW, barY), strokeWidth = 3.dp.toPx())
                val t0 = barX0 + (viewStart.toFloat() / timelineFrames) * barW
                val t1 = barX0 + ((viewStart + visible).toFloat() / timelineFrames) * barW
                drawLine(scrollThumb, Offset(t0, barY), Offset(t1.coerceAtLeast(t0 + 6.dp.toPx()), barY), strokeWidth = 3.dp.toPx())
            }
        }
        if (zoom > 1.01f) {
            Surface(
                onClick = { zoom = 1f; viewStart = 0L },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text(
                    "%.1f× · fit".format(zoom),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** How close to either end of the box a zoomed drag starts gliding the view (as the Advanced ruler). */
private val EdgeScrollZone = 40.dp

/** Glide speed at (and past) the very edge, in dp per second. */
private const val EdgeScrollMaxDpPerSecond = 900f

private val HandleTabWidth = 14.dp
private val HandleTabHeight = 30.dp
private val HandleTabInset = 8.dp

/**
 * A take being recorded, voice-recorder style: the take's real waveform
 * (its peaks, read from the file as it's written) scrolls in from the right
 * to a fixed centre line, newest at the line. When the take adds on to a
 * note, the note's end leads up to it in the track's colour, so it reads as
 * one recording growing.
 */
@Composable
private fun LiveRecordingWave(engine: AudioEngine, rec: RecordingPreview, previous: MultitrackTrackSpec?, modifier: Modifier) {
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val previousColor = previous?.let { lerp(trackColor(it.colorIndex), Color.Black, 0.25f).copy(alpha = 0.7f) } ?: Color.Transparent
    val pyramids = remember(previous?.clips) { previous?.clips?.map { engine.buildPeakPyramid(it.buffer) } ?: emptyList() }
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(RecordRed.copy(alpha = 0.06f))
            .border(1.5.dp, RecordRed.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
    ) {
        val mid = size.height / 2f
        val half = mid - 12.dp.toPx()
        val centre = size.width / 2f
        // One peak every 3dp: at 50 ms a peak, about a second per 60dp.
        val step = 3.dp.toPx()
        val barW = 2.dp.toPx()
        drawLine(baseline, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        val peaks = rec.peaks
        val shown = minOf((centre / step).toInt() + 1, peaks.size)
        for (k in 0 until shown) {
            val peak = peaks[peaks.size - 1 - k].coerceIn(0f, 1f)
            val h = (peak * half).coerceAtLeast(1.dp.toPx())
            drawRoundRect(RecordRed, topLeft = Offset(centre - k * step - barW, mid - h), size = Size(barW, h * 2f), cornerRadius = CornerRadius(barW / 2f))
        }
        // The note this take adds on to, ending where the new audio starts.
        val newStartX = centre - peaks.size * step
        if (previous != null && newStartX > 0f) {
            val framesPerPx = rec.framesPerPeak / step.toDouble()
            previous.clips.forEachIndexed { i, clip ->
                val x1 = newStartX - ((rec.startFrame - (clip.startFrame + clip.lengthFrames)) / framesPerPx).toFloat()
                val x0 = x1 - (clip.lengthFrames / framesPerPx).toFloat()
                if (x1 < 0f || x0 > newStartX) return@forEachIndexed
                val skipPx = (-x0).coerceAtLeast(0f)
                clipRect(left = x0.coerceAtLeast(0f), right = x1.coerceAtMost(newStartX)) {
                    translate(left = x0 + skipPx) {
                        drawClipWaveform(
                            clip = clip,
                            pyramid = pyramids[i],
                            firstFrameInClip = (skipPx * framesPerPx).toLong(),
                            framesPerPx = framesPerPx,
                            top = mid - half,
                            height = half * 2f,
                            color = previousColor,
                        )
                    }
                }
            }
        }
        drawLine(RecordRed, Offset(centre, 0f), Offset(centre, size.height), strokeWidth = 2.dp.toPx())
    }
}

/**
 * Simple mode's transport, the cozy part: a big record button, play, and a
 * metronome pill (tap the metronome to switch the click; tap the tempo to
 * change it; count-in in its menu), under the clock and the seeker. [wide]
 * (landscape) lays it all out as one bar along the bottom, leaving the
 * short screen's height to the waveform.
 */
@Composable
private fun SimpleTransport(
    wide: Boolean,
    isRecording: Boolean,
    isArmed: Boolean,
    countInBeatsRemaining: Int,
    isPlaying: Boolean,
    canPlay: Boolean,
    positionFrame: Long,
    timelineFrames: Long,
    onSeek: (Long, Boolean) -> Unit,
    timeCaption: String?,
    hasNote: Boolean,
    /** The playhead is inside the note, so Record would record over what's there. */
    recordsOver: Boolean,
    meterPeakMilli: Int,
    meterClipped: Boolean,
    metronomeOn: Boolean,
    bpm: Double,
    countInBars: Int,
    editsEnabled: Boolean,
    onRecord: () -> Unit,
    onPlay: () -> Unit,
    onMetronomeToggle: () -> Unit,
    onTempoClick: () -> Unit,
    onCountInChange: (Int) -> Unit,
    tutorialTargets: TutorialTargets?,
    modifier: Modifier,
) {
    val tenths = positionFrame * 10 / kSampleRate
    val clock: @Composable () -> Unit = {
        Text(
            "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10),
            style = (if (wide) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium)
                .copy(fontFamily = FontFamily.Monospace),
            color = if (isRecording) RecordRed else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            when {
                isArmed && countInBeatsRemaining > 0 -> "Count-in… $countInBeatsRemaining"
                isArmed -> "Get ready…"
                isRecording -> timeCaption ?: "Recording"
                isPlaying -> "Playing"
                !hasNote -> "Tap ● to record a voice note"
                recordsOver -> "Tap ● to record from here, over what's there"
                else -> "Tap ● to keep recording"
            },
            style = MaterialTheme.typography.bodySmall,
            color = SongNotesTheme.colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val progress: @Composable (Modifier) -> Unit = { m ->
        if (isRecording) {
            Box(modifier = m.height(28.dp), contentAlignment = Alignment.Center) {
                InputLevelMeter(meterPeakMilli, meterClipped, modifier = Modifier.fillMaxWidth().height(6.dp))
            }
        } else {
            SeekBar(positionFrame = positionFrame, timelineFrames = timelineFrames, onSeek = onSeek, modifier = m)
        }
    }
    val buttons: @Composable () -> Unit = {
        MetronomePill(
            on = metronomeOn,
            bpm = bpm,
            countInBars = countInBars,
            enabled = editsEnabled,
            onToggle = onMetronomeToggle,
            onTempoClick = onTempoClick,
            onCountInChange = onCountInChange,
            modifier = Modifier.tutorialTarget(tutorialTargets, "simple_metronome"),
        )
        BigRecordButton(isRecording, enabled = !isPlaying, onClick = onRecord, modifier = Modifier.tutorialTarget(tutorialTargets, "simple_record"))
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(enabled = !isRecording && (isPlaying || canPlay), onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Stop" else "Play",
                tint = if (!isRecording && (isPlaying || canPlay)) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(30.dp),
            )
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        if (wide) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.width(200.dp)) { clock() }
                progress(Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                clock()
                Spacer(Modifier.height(if (isRecording) 8.dp else 6.dp))
                progress(if (isRecording) Modifier.width(180.dp) else Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) { buttons() }
            }
        }
    }
}

/**
 * The seeker: the playhead's position across the whole demo. Tap or drag
 * anywhere along it; during playback, letting go jumps playback there.
 */
@Composable
internal fun SeekBar(positionFrame: Long, timelineFrames: Long, onSeek: (Long, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val currentOnSeek by rememberUpdatedState(onSeek)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    // The line is inset by the thumb's radius, so the thumb stays inside the
    // bar's bounds at either end instead of drawing over its neighbours.
    val thumbRadius = 8.dp
    Canvas(
        modifier = modifier
            .height(28.dp)
            .pointerInput(timelineFrames) {
                val inset = thumbRadius.toPx()
                fun frameAt(x: Float): Long =
                    ((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f) * timelineFrames).toLong().coerceIn(0L, timelineFrames)
                detectTapGestures { currentOnSeek(frameAt(it.x), true) }
            }
            .pointerInput(timelineFrames) {
                val inset = thumbRadius.toPx()
                fun frameAt(x: Float): Long =
                    ((x - inset) / (size.width - 2 * inset).coerceAtLeast(1f) * timelineFrames).toLong().coerceIn(0L, timelineFrames)
                var last = 0L
                detectHorizontalDragGestures(
                    onDragEnd = { currentOnSeek(last, true) },
                    onDragCancel = { currentOnSeek(last, true) },
                ) { change, _ ->
                    change.consume()
                    last = frameAt(change.position.x)
                    currentOnSeek(last, false)
                }
            },
    ) {
        val mid = size.height / 2f
        val h = 4.dp.toPx()
        val r = thumbRadius.toPx()
        val width = (size.width - 2 * r).coerceAtLeast(0f)
        val x = r + (positionFrame.toDouble() / timelineFrames * width).toFloat().coerceIn(0f, width)
        drawRoundRect(track, topLeft = Offset(r, mid - h / 2f), size = Size(width, h), cornerRadius = CornerRadius(h / 2f))
        drawRoundRect(fill, topLeft = Offset(r, mid - h / 2f), size = Size(x - r, h), cornerRadius = CornerRadius(h / 2f))
        drawCircle(fill, radius = r, center = Offset(x, mid))
    }
}

private fun formatClockFrames(frame: Long): String {
    val tenths = frame * 10 / kSampleRate
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
}

@Composable
private fun BigRecordButton(isRecording: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(80.dp)
            .clip(CircleShape)
            .border(4.dp, RecordRed.copy(alpha = if (enabled) 0.35f else 0.12f), CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(if (enabled) RecordRed else RecordRed.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = if (isRecording) "Stop recording" else "Record"
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        // Red dot when idle, a white "stop" square while recording.
        if (isRecording) Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
    }
}

@Composable
private fun MetronomePill(
    on: Boolean,
    bpm: Double,
    countInBars: Int,
    enabled: Boolean,
    onToggle: () -> Unit,
    onTempoClick: () -> Unit,
    onCountInChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.clip(CircleShape).clickable(onClick = onToggle).padding(4.dp)) {
                    Icon(
                        MetronomeIcon,
                        contentDescription = if (on) "Metronome on" else "Metronome off",
                        tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Box(modifier = Modifier.clip(CircleShape).clickable(enabled = enabled) { open = true }.padding(4.dp)) {
                    Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "${formatBpm(bpm)} bpm",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onTempoClick).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Count-in",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            for (bars in 0..2) {
                DropdownMenuItem(
                    text = { Text(countInLabel(bars)) },
                    leadingIcon = { RadioButton(selected = bars == countInBars, onClick = null) },
                    onClick = { onCountInChange(bars); open = false },
                )
            }
        }
    }
}
