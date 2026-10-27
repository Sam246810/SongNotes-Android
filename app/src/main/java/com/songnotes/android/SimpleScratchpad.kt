package com.songnotes.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.MultitrackTrackSpec

private const val kSampleRate = 48000

/** Shortest timeline Simple mode shows, so a first two-second idea doesn't stretch across the whole card. */
private const val kMinTimelineFrames = 15L * kSampleRate

/**
 * Ruled notebook paper behind Simple mode: faint horizontal rules and a
 * margin line, in the editor's own paper-line colour, so the Scratchpad
 * reads as part of the same notebook as the lyrics.
 */
fun Modifier.notebookPaper(rule: Color, margin: Color, spacing: Dp = 34.dp, marginInset: Dp = 22.dp): Modifier =
    drawBehind {
        val step = spacing.toPx()
        var y = step
        while (y < size.height) {
            drawLine(rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            y += step
        }
        val x = marginInset.toPx()
        drawLine(margin, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
    }

/**
 * Simple mode: a multitrack voice-notes recorder with a metronome. Every
 * take is a new layer that plays along with the ones before it; there are no
 * bars, beats or grid. Layers stack as cards on ruled paper, each with its
 * whole-song waveform, mute, solo and volume. Tap a waveform to move the
 * playhead; that's where play starts and where the next layer begins.
 *
 * It edits the same project as Advanced mode, so switching never loses
 * anything (a song's demo is one thing, shown two ways).
 */
@Composable
fun SimpleScratchpad(
    engine: AudioEngine,
    tracks: List<MultitrackTrackSpec>,
    songTitle: String,
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
    onMuteToggle: (Int) -> Unit,
    onSoloToggle: (Int) -> Unit,
    onGainChange: (Int, Float) -> Unit,
    onRename: (Int, String) -> Unit,
    onDelete: (Int) -> Unit,
    onTrim: (index: Int, startFrame: Long, endFrame: Long) -> Unit,
    onSwitchToAdvanced: () -> Unit,
    onExport: () -> Unit,
    onCalibrate: () -> Unit,
    onTutorial: () -> Unit,
    tutorialTargets: TutorialTargets?,
    modifier: Modifier = Modifier,
) {
    val projectEnd = tracks.maxOfOrNull { t -> t.clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L } ?: 0L
    val recordingEnd = recording?.let { it.startFrame + it.lengthFrames } ?: 0L
    val timelineFrames = maxOf(projectEnd, recordingEnd, kMinTimelineFrames)
    val paper = SongNotesTheme.colors
    val marginColor = Color(0xFFD27D6B).copy(alpha = 0.28f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(paper.parchment)
            .notebookPaper(paper.paperLine, marginColor),
    ) {
        SimpleTopBar(
            songTitle = songTitle,
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
            onTutorial = onTutorial,
        )

        val layers: @Composable (Modifier) -> Unit = { m ->
            LayerList(
                engine = engine,
                tracks = tracks,
                timelineFrames = timelineFrames,
                positionFrame = positionFrame,
                recording = recording,
                editsEnabled = editsEnabled,
                onSeek = onSeek,
                onMuteToggle = onMuteToggle,
                onSoloToggle = onSoloToggle,
                onGainChange = onGainChange,
                onRename = onRename,
                onDelete = onDelete,
                onTrim = onTrim,
                tutorialTargets = tutorialTargets,
                modifier = m.tutorialTarget(tutorialTargets, "simple_layers"),
            )
        }
        val transport: @Composable (Modifier) -> Unit = { m ->
            SimpleTransport(
                vertical = landscape,
                isRecording = isRecording,
                isArmed = isArmed,
                countInBeatsRemaining = countInBeatsRemaining,
                isPlaying = isPlaying,
                canPlay = projectEnd > 0L,
                positionFrame = positionFrame,
                timelineFrames = timelineFrames,
                onSeek = onSeek,
                timeCaption = timeCaption,
                layerCount = tracks.size,
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
        if (landscape) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                layers(Modifier.weight(1f).fillMaxHeight())
                transport(Modifier.width(260.dp).fillMaxHeight().padding(12.dp))
            }
        } else {
            layers(Modifier.weight(1f).fillMaxWidth())
            transport(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SimpleTopBar(
    songTitle: String,
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
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text("Demo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (songTitle.isNotBlank()) {
                Text(
                    songTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SongNotesTheme.colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(enabled = canUndo, onClick = onUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
        IconButton(enabled = canRedo, onClick = onRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Advanced mode") },
                    leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                    enabled = editsEnabled,
                    onClick = { menuOpen = false; onSwitchToAdvanced() },
                )
                DropdownMenuItem(
                    text = { Text(if (isExporting) "Exporting…" else "Export mixdown (WAV)") },
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

@Composable
private fun LayerList(
    engine: AudioEngine,
    tracks: List<MultitrackTrackSpec>,
    timelineFrames: Long,
    positionFrame: Long,
    recording: RecordingPreview?,
    editsEnabled: Boolean,
    onSeek: (Long, Boolean) -> Unit,
    onMuteToggle: (Int) -> Unit,
    onSoloToggle: (Int) -> Unit,
    onGainChange: (Int, Float) -> Unit,
    onRename: (Int, String) -> Unit,
    onDelete: (Int) -> Unit,
    onTrim: (Int, Long, Long) -> Unit,
    tutorialTargets: TutorialTargets?,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    // A new layer being recorded appears at the bottom; keep it in view.
    LaunchedEffect(recording != null) {
        if (recording != null) listState.animateScrollToItem(tracks.size)
    }
    if (tracks.isEmpty() && recording == null) {
        EmptyDemo(modifier)
        return
    }
    val anySolo = tracks.any { it.soloed }
    val firstWithAudio = tracks.indexOfFirst { it.clips.isNotEmpty() }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 36.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(tracks, key = { i, t -> "${t.name}#${t.colorIndex}#$i" }) { index, track ->
            LayerCard(
                engine = engine,
                index = index,
                track = track,
                audible = !track.muted && (!anySolo || track.soloed),
                timelineFrames = timelineFrames,
                positionFrame = positionFrame,
                editsEnabled = editsEnabled,
                onSeek = onSeek,
                onMuteToggle = { onMuteToggle(index) },
                onSoloToggle = { onSoloToggle(index) },
                onGainChange = { onGainChange(index, it) },
                onRename = { onRename(index, it) },
                onDelete = { onDelete(index) },
                onTrim = { start, end -> onTrim(index, start, end) },
                waveformModifier = if (index == firstWithAudio) {
                    Modifier.tutorialTarget(tutorialTargets, "first_layer_wave")
                } else {
                    Modifier
                },
            )
        }
        if (recording != null) {
            item(key = "recording") {
                RecordingLayerCard(recording, timelineFrames)
            }
        }
    }
}

@Composable
private fun EmptyDemo(modifier: Modifier) {
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
        Text("Hum it before it's gone", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap the red button to record your first layer. Every take after that stacks on top and plays along.",
            style = MaterialTheme.typography.bodyMedium,
            color = SongNotesTheme.colors.muted,
        )
    }
}

/** One layer (track): name, length, mute/solo/menu, and its waveform across the whole demo. */
@Composable
private fun LayerCard(
    engine: AudioEngine,
    index: Int,
    track: MultitrackTrackSpec,
    audible: Boolean,
    timelineFrames: Long,
    positionFrame: Long,
    editsEnabled: Boolean,
    onSeek: (Long, Boolean) -> Unit,
    onMuteToggle: () -> Unit,
    onSoloToggle: () -> Unit,
    onGainChange: (Float) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onTrim: (startFrame: Long, endFrame: Long) -> Unit,
    waveformModifier: Modifier = Modifier,
) {
    val color = trackColor(track.colorIndex)
    val pyramids = remember(track.clips) { track.clips.map { engine.buildPeakPyramid(it.buffer) } }
    val length = track.clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    var draggingGain by remember { mutableStateOf<Float?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)
    val waveColor = lerp(color, Color.Black, 0.25f)
    val playheadColor = MaterialTheme.colorScheme.primary
    // Trim mode (voice-notes style): the layer's kept window, while editing.
    val layerStart = track.clips.minOfOrNull { it.startFrame } ?: 0L
    var trim by remember(track.clips) { mutableStateOf<Pair<Long, Long>?>(null) }
    val currentTrim by rememberUpdatedState(trim)
    val handleColor = MaterialTheme.colorScheme.primary

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().alpha(if (audible) 1f else 0.5f),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        track.name.ifEmpty { "Layer ${index + 1}" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (length > 0L) formatElapsed(((length + kSampleRate - 1) / kSampleRate).toInt()) else "Empty",
                        style = MaterialTheme.typography.labelSmall,
                        color = SongNotesTheme.colors.muted,
                    )
                }
                // Mute/solo/volume stay live during playback and recording.
                IconButton(onClick = onMuteToggle) {
                    Icon(
                        if (track.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (track.muted) "Unmute" else "Mute",
                        tint = if (track.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSoloToggle) {
                    Icon(
                        if (track.soloed) Icons.Filled.Headset else Icons.Filled.HeadsetOff,
                        contentDescription = if (track.soloed) "Unsolo" else "Solo",
                        tint = if (track.soloed) Color(0xFF3D8FD8) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Layer options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Trim") },
                            leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                            enabled = editsEnabled && length > layerStart,
                            onClick = { menuOpen = false; trim = layerStart to length },
                        )
                        DropdownMenuItem(text = { Text("Rename") }, enabled = editsEnabled, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(text = { Text("Volume") }, onClick = { menuOpen = false; volumeOpen = true })
                        DropdownMenuItem(
                            text = { Text("Delete layer", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            enabled = editsEnabled,
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
            Canvas(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.16f))
                    .then(waveformModifier)
                    .pointerInput(timelineFrames, trim != null) {
                        if (currentTrim != null) return@pointerInput
                        detectTapGestures { offset ->
                            currentOnSeek((offset.x / size.width * timelineFrames).toLong().coerceAtLeast(0L), true)
                        }
                    }
                    .pointerInput(timelineFrames, trim != null, layerStart, length) {
                        fun frameAt(x: Float) = (x / size.width * timelineFrames).toLong().coerceIn(0L, timelineFrames)
                        if (currentTrim == null) {
                            // Drag along a layer to scrub the playhead (sideways
                            // only, so the list still scrolls vertically).
                            var last = 0L
                            detectHorizontalDragGestures(
                                onDragEnd = { currentOnSeek(last, true) },
                                onDragCancel = { currentOnSeek(last, true) },
                            ) { change, _ ->
                                change.consume()
                                last = frameAt(change.position.x)
                                currentOnSeek(last, false)
                            }
                        } else {
                            // Drag whichever trim handle is nearer the finger.
                            var movingStart = true
                            val minFrames = kSampleRate / 10L
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    val (a, b) = currentTrim ?: return@detectHorizontalDragGestures
                                    val f = frameAt(offset.x)
                                    movingStart = kotlin.math.abs(f - a) <= kotlin.math.abs(f - b)
                                },
                            ) { change, _ ->
                                change.consume()
                                val (a, b) = currentTrim ?: return@detectHorizontalDragGestures
                                val f = frameAt(change.position.x)
                                trim = if (movingStart) {
                                    f.coerceIn(layerStart, b - minFrames) to b
                                } else {
                                    a to f.coerceIn(a + minFrames, length)
                                }
                            }
                        }
                    },
            ) {
                val framesPerPx = timelineFrames.toDouble() / size.width
                track.clips.forEachIndexed { i, clip ->
                    val x0 = (clip.startFrame / framesPerPx).toFloat()
                    val w = (clip.lengthFrames / framesPerPx).toFloat()
                    drawRoundRect(color.copy(alpha = 0.22f), topLeft = Offset(x0, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(8f))
                    clipRect(left = x0, right = x0 + w) {
                        translate(left = x0) {
                            drawClipWaveform(
                                clip = clip,
                                pyramid = pyramids[i],
                                firstFrameInClip = 0L,
                                framesPerPx = framesPerPx,
                                top = 4f,
                                height = size.height - 8f,
                                color = waveColor,
                            )
                        }
                    }
                }
                val window = trim
                if (window != null) {
                    // Dim what will be cut, and draw the two handles.
                    val xs = (window.first / framesPerPx).toFloat()
                    val xe = (window.second / framesPerPx).toFloat()
                    val dim = Color.Black.copy(alpha = 0.35f)
                    drawRect(dim, topLeft = Offset(0f, 0f), size = Size(xs, size.height))
                    drawRect(dim, topLeft = Offset(xe, 0f), size = Size(size.width - xe, size.height))
                    val w = 6.dp.toPx()
                    drawRoundRect(handleColor, topLeft = Offset(xs, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(w / 2f))
                    drawRoundRect(handleColor, topLeft = Offset(xe - w, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(w / 2f))
                } else {
                    // The playhead, with a knob on top so it reads as something to grab.
                    val px = (positionFrame.toDouble() / timelineFrames * size.width).toFloat()
                    drawLine(playheadColor, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.5.dp.toPx())
                    drawCircle(playheadColor, radius = 5.dp.toPx(), center = Offset(px, 5.dp.toPx()))
                }
            }
            trim?.let { (start, end) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Keep ${formatClockFrames(start)} – ${formatClockFrames(end)}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = SongNotesTheme.colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { trim = null }) { Text("Cancel") }
                    Button(onClick = { onTrim(start, end); trim = null }) { Text("Trim") }
                }
            }
        }
    }

    if (renaming) {
        var text by remember { mutableStateOf(track.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename layer") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(32) }, singleLine = true) },
            confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onRename(text.trim()); renaming = false }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (volumeOpen) {
        AlertDialog(
            onDismissRequest = { volumeOpen = false },
            title = { Text("${track.name} volume") },
            text = {
                Slider(
                    value = draggingGain ?: track.gain,
                    onValueChange = { draggingGain = it },
                    onValueChangeFinished = { draggingGain?.let(onGainChange); draggingGain = null },
                    valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
                )
            },
            confirmButton = { TextButton(onClick = { volumeOpen = false }) { Text("Done") } },
        )
    }
}

/** The layer being recorded, growing as you sing, drawn from live input levels. */
@Composable
private fun RecordingLayerCard(rec: RecordingPreview, timelineFrames: Long) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = RecordRed.copy(alpha = 0.08f),
        border = BorderStroke(1.5.dp, RecordRed.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = RecordRed, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(8.dp))
                Text("Recording a new layer…", style = MaterialTheme.typography.titleSmall, color = RecordRed)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(RecordRed.copy(alpha = 0.08f))) {
                val pxPerFrame = size.width / timelineFrames.toFloat()
                val x0 = rec.startFrame * pxPerFrame
                val barW = (rec.framesPerPeak * pxPerFrame).coerceAtLeast(1f)
                val mid = size.height / 2f
                rec.peaks.forEachIndexed { i, peak ->
                    val h = (peak.coerceIn(0f, 1f) * (mid - 3f)).coerceAtLeast(1f)
                    drawRect(RecordRed, topLeft = Offset(x0 + i * barW, mid - h), size = Size(barW * 0.8f, h * 2f))
                }
            }
        }
    }
}

/**
 * Simple mode's transport, the cozy part: a big record button, play, and a
 * metronome pill (tap the metronome to switch the click; tap the tempo to
 * change it; count-in in its menu). [vertical] stacks it beside the layers in
 * landscape.
 */
@Composable
private fun SimpleTransport(
    vertical: Boolean,
    isRecording: Boolean,
    isArmed: Boolean,
    countInBeatsRemaining: Int,
    isPlaying: Boolean,
    canPlay: Boolean,
    positionFrame: Long,
    timelineFrames: Long,
    onSeek: (Long, Boolean) -> Unit,
    timeCaption: String?,
    layerCount: Int,
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
    Surface(
        modifier = modifier,
        shape = if (vertical) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (vertical) Arrangement.SpaceEvenly else Arrangement.Top,
        ) {
            val tenths = positionFrame * 10 / kSampleRate
            Text(
                "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10),
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                color = if (isRecording) RecordRed else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when {
                    isArmed && countInBeatsRemaining > 0 -> "Count-in… $countInBeatsRemaining"
                    isArmed -> "Get ready…"
                    isRecording -> timeCaption ?: "Recording a new layer"
                    isPlaying -> "Playing"
                    layerCount == 0 -> "Tap ● to record your first layer"
                    else -> "Tap ● to add another layer"
                },
                style = MaterialTheme.typography.bodySmall,
                color = SongNotesTheme.colors.muted,
            )
            if (isRecording) {
                Spacer(Modifier.height(8.dp))
                InputLevelMeter(meterPeakMilli, meterClipped, modifier = Modifier.width(180.dp).height(6.dp))
            } else {
                Spacer(Modifier.height(6.dp))
                SeekBar(
                    positionFrame = positionFrame,
                    timelineFrames = timelineFrames,
                    onSeek = onSeek,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(if (vertical) 8.dp else 14.dp))
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
            if (vertical) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { buttons() }
            } else {
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
private fun SeekBar(positionFrame: Long, timelineFrames: Long, onSeek: (Long, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val currentOnSeek by rememberUpdatedState(onSeek)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .height(28.dp)
            .pointerInput(timelineFrames) {
                detectTapGestures { currentOnSeek((it.x / size.width * timelineFrames).toLong().coerceIn(0L, timelineFrames), true) }
            }
            .pointerInput(timelineFrames) {
                var last = 0L
                detectHorizontalDragGestures(
                    onDragEnd = { currentOnSeek(last, true) },
                    onDragCancel = { currentOnSeek(last, true) },
                ) { change, _ ->
                    change.consume()
                    last = (change.position.x / size.width * timelineFrames).toLong().coerceIn(0L, timelineFrames)
                    currentOnSeek(last, false)
                }
            },
    ) {
        val mid = size.height / 2f
        val h = 4.dp.toPx()
        val x = (positionFrame.toDouble() / timelineFrames * size.width).toFloat().coerceIn(0f, size.width)
        drawRoundRect(track, topLeft = Offset(0f, mid - h / 2f), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2f))
        drawRoundRect(fill, topLeft = Offset(0f, mid - h / 2f), size = Size(x, h), cornerRadius = CornerRadius(h / 2f))
        drawCircle(fill, radius = 8.dp.toPx(), center = Offset(x, mid))
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
                contentDescription = if (isRecording) "Stop recording" else "Record a layer"
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
