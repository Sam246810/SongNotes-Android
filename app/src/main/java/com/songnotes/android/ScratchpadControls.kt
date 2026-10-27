package com.songnotes.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.songnotes.core.audio.MultitrackTrackSpec
import kotlin.math.log10

/** Ableton's solo blue. */
private val SoloBlue = Color(0xFF3D8FD8)
/** Mute reads as "switched off", Ableton's grey. */
private val MuteGray = Color(0xFF7A7A7A)
private val MeterGreen = Color(0xFF5FA35A)
/** Ableton's record/arm red. Not the theme's error colour, which is pink in dark mode. */
val RecordRed = Color(0xFFE5483B)
private val MeterAmber = Color(0xFFE0A030)

/** A metronome glyph (Material has none): a pyramid body with its pendulum arm. */
val MetronomeIcon: ImageVector by lazy {
    ImageVector.Builder("Metronome", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(9.2f, 2.5f); lineTo(14.8f, 2.5f); lineTo(19.5f, 21.5f); lineTo(4.5f, 21.5f); close()
            moveTo(10.4f, 4.5f); lineTo(7.1f, 17.5f); lineTo(16.9f, 17.5f); lineTo(13.6f, 4.5f); close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(11.2f, 15.8f); lineTo(16.6f, 6.2f); lineTo(18.2f, 7.1f); lineTo(12.8f, 16.7f); close()
        }
    }.build()
}

/** Whole-number BPM ("120") when it is one, else one decimal place ("120.5"). */
fun formatBpm(bpm: Double): String =
    if (bpm == bpm.toLong().toDouble()) bpm.toLong().toString() else "%.1f".format(bpm)

/**
 * The Scratchpad's control bar, laid out like Ableton's: tempo, time
 * signature, metronome (with Count-in in its menu, where Ableton keeps it)
 * and the grid switch on the left; transport and the position readout in the
 * middle; undo/redo and an overflow on the right. [compact] (portrait) wraps
 * it onto two rows.
 */
@Composable
fun ScratchpadControlBar(
    compact: Boolean,
    isRecording: Boolean,
    isArmed: Boolean,
    countInBeatsRemaining: Int,
    isPlaying: Boolean,
    canPlay: Boolean,
    canRecord: Boolean,
    bpm: Double,
    beatsPerBar: Int,
    metronomeOn: Boolean,
    countInBars: Int,
    snapOn: Boolean,
    position: String,
    time: String,
    canUndo: Boolean,
    canRedo: Boolean,
    editsEnabled: Boolean,
    isExporting: Boolean,
    portraitMode: Boolean,
    onBack: () -> Unit,
    onTempoClick: () -> Unit,
    onBeatsPerBarChange: (Int) -> Unit,
    onMetronomeToggle: () -> Unit,
    onCountInChange: (Int) -> Unit,
    onSnapToggle: () -> Unit,
    onPlay: () -> Unit,
    onRecord: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    onToggleOrientation: () -> Unit,
    onCalibrate: () -> Unit,
    onTutorial: () -> Unit,
) {
    val left: @Composable () -> Unit = {
        if (isRecording) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Lyrics")
            }
        } else {
            IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = "Back to lyrics") }
        }
        ControlChip(text = "${formatBpm(bpm)} BPM", enabled = editsEnabled, onClick = onTempoClick)
        TimeSignatureChip(beatsPerBar, enabled = editsEnabled, onChange = onBeatsPerBarChange)
        MetronomeButton(metronomeOn, countInBars, editsEnabled, onMetronomeToggle, onCountInChange)
        IconButton(onClick = onSnapToggle) {
            Icon(
                if (snapOn) Icons.Filled.GridOn else Icons.Filled.GridOff,
                contentDescription = if (snapOn) "Snap to grid: on" else "Snap to grid: off",
                tint = if (snapOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val transport: @Composable () -> Unit = {
        TransportButton(
            icon = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            label = if (isPlaying) "Stop" else "Play",
            enabled = !isRecording && (isPlaying || canPlay),
            active = isPlaying,
            activeColor = MaterialTheme.colorScheme.primary,
            onClick = onPlay,
        )
        Spacer(Modifier.width(8.dp))
        TransportButton(
            icon = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            label = if (isRecording) "Stop recording" else "Record",
            enabled = !isPlaying && (isRecording || canRecord),
            active = isRecording,
            activeColor = RecordRed,
            idleTint = RecordRed,
            onClick = onRecord,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                when {
                    isArmed && countInBeatsRemaining > 0 -> "Count-in $countInBeatsRemaining"
                    isArmed -> "Starting…"
                    else -> position
                },
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (isRecording) RecordRed else MaterialTheme.colorScheme.onSurface,
            )
            Text(time, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val right: @Composable () -> Unit = {
        IconButton(enabled = canUndo, onClick = onUndo) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo") }
        IconButton(enabled = canRedo, onClick = onRedo) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo") }
        OverflowMenu(isExporting, editsEnabled, portraitMode, onExport, onToggleOrientation, onCalibrate, onTutorial)
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        if (compact) {
            // Two rows: settings on top, transport + undo/redo/menu below, so
            // nothing (above all the menu, the way back to landscape) gets
            // pushed off a narrow screen.
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { left() }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    transport()
                    Spacer(Modifier.weight(1f))
                    right()
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                left()
                Spacer(Modifier.weight(1f))
                transport()
                Spacer(Modifier.weight(1f))
                right()
            }
        }
    }
}

@Composable
private fun ControlChip(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), maxLines = 1)
    }
}

@Composable
private fun TimeSignatureChip(beatsPerBar: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ControlChip(text = "$beatsPerBar/4", enabled = enabled, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (beats in 2..7) {
                DropdownMenuItem(
                    text = { Text("$beats/4") },
                    leadingIcon = { RadioButton(selected = beats == beatsPerBar, onClick = null) },
                    onClick = { onChange(beats); open = false },
                )
            }
        }
    }
}

/**
 * Metronome on/off (tap) with the Count-in choice in its menu (long-press or
 * the small arrow), the way Ableton's metronome button carries it.
 */
@Composable
private fun MetronomeButton(
    on: Boolean,
    countInBars: Int,
    enabled: Boolean,
    onToggle: () -> Unit,
    onCountIn: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Icon(
                    MetronomeIcon,
                    contentDescription = if (on) "Metronome on" else "Metronome off",
                    tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                modifier = Modifier.clickable(enabled = enabled) { open = true }.padding(end = 6.dp, top = 5.dp, bottom = 5.dp),
            ) {
                Text("▾", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                    onClick = { onCountIn(bars); open = false },
                )
            }
        }
    }
}

fun countInLabel(bars: Int): String = when (bars) {
    0 -> "None"
    1 -> "1 bar"
    else -> "$bars bars"
}

@Composable
private fun TransportButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    idleTint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) activeColor else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                active -> Color.White
                else -> idleTint
            },
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun OverflowMenu(
    isExporting: Boolean,
    editsEnabled: Boolean,
    portraitMode: Boolean,
    onExport: () -> Unit,
    onToggleOrientation: () -> Unit,
    onCalibrate: () -> Unit,
    onTutorial: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (isExporting) "Exporting…" else "Export mixdown (WAV)") },
                leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                enabled = editsEnabled && !isExporting,
                onClick = { open = false; onExport() },
            )
            DropdownMenuItem(
                text = { Text(if (portraitMode) "Landscape view" else "Portrait view") },
                leadingIcon = { Icon(Icons.Filled.ScreenRotation, contentDescription = null) },
                onClick = { open = false; onToggleOrientation() },
            )
            DropdownMenuItem(
                text = { Text("Calibrate recording latency") },
                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                enabled = editsEnabled,
                onClick = { open = false; onCalibrate() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Scratchpad tutorial") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                onClick = { open = false; onTutorial() },
            )
        }
    }
}

/**
 * One track's header, beside its lane (Ableton's track header): colour
 * strip, name (tap for rename/volume/delete), and Arm / M / S. [compact]
 * (portrait) drops the inline volume slider; it's in the name menu instead.
 * The armed track shows the input meter while recording.
 */
@Composable
fun TrackHeader(
    index: Int,
    track: MultitrackTrackSpec,
    compact: Boolean,
    armed: Boolean,
    enabled: Boolean,
    showMeter: Boolean,
    meterPeakMilli: Int,
    meterClipped: Boolean,
    onArm: () -> Unit,
    onMuteToggle: () -> Unit,
    onSoloToggle: () -> Unit,
    onGainChange: (Float) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val color = trackColor(track.colorIndex)
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    var draggingGain by remember { mutableStateOf<Float?>(null) }

    Row(modifier = Modifier.fillMaxHeight()) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(color))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    Text(
                        track.name.ifEmpty { "${index + 1} Audio" },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = enabled) { menuOpen = true },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                        if (compact) DropdownMenuItem(text = { Text("Volume") }, onClick = { menuOpen = false; volumeOpen = true })
                        DropdownMenuItem(
                            text = { Text("Delete track", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "Long-press to reorder",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(16.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaderToggle(
                    label = null,
                    icon = Icons.Filled.FiberManualRecord,
                    on = armed,
                    onColor = RecordRed,
                    description = if (armed) "Armed for recording" else "Arm for recording",
                    enabled = enabled,
                    onClick = onArm,
                )
                HeaderToggle("M", null, track.muted, MuteGray, if (track.muted) "Unmute" else "Mute", enabled, onMuteToggle)
                HeaderToggle("S", null, track.soloed, SoloBlue, if (track.soloed) "Unsolo" else "Solo", enabled, onSoloToggle)
                if (showMeter) {
                    Spacer(Modifier.width(2.dp))
                    InputLevelMeter(meterPeakMilli, meterClipped, modifier = Modifier.weight(1f).height(6.dp))
                }
            }
            if (!compact) {
                Slider(
                    value = draggingGain ?: track.gain,
                    onValueChange = { draggingGain = it },
                    onValueChangeFinished = { draggingGain?.let(onGainChange); draggingGain = null },
                    valueRange = 0f..2f,
                    enabled = enabled,
                    colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color),
                    modifier = Modifier.fillMaxWidth().height(20.dp),
                )
            }
        }
    }

    if (renaming) {
        RenameTrackDialog(track.name, onDismiss = { renaming = false }, onRename = { onRename(it); renaming = false })
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

@Composable
private fun HeaderToggle(
    label: String?,
    icon: ImageVector?,
    on: Boolean,
    onColor: Color,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) onColor else MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, if (on) onColor else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        if (icon != null) {
            Icon(icon, contentDescription = description, tint = if (on) Color.White else onColor, modifier = Modifier.size(14.dp))
        } else if (label != null) {
            Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RenameTrackDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename track") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(32) }, singleLine = true) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onRename(text.trim()) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Tempo entry; the song's chart BPM on the lyrics page still drives it when set. */
@Composable
fun TempoDialog(bpm: Double, onDismiss: () -> Unit, onSet: (Double) -> Unit) {
    var text by remember { mutableStateOf(formatBpm(bpm)) }
    val parsed = text.toDoubleOrNull()?.takeIf { it in 20.0..400.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tempo") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { parsed?.let { text = formatBpm((it - 1).coerceAtLeast(20.0)) } }) { Text("−", fontSize = 20.sp) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(6) },
                    singleLine = true,
                    suffix = { Text("BPM") },
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(140.dp),
                )
                IconButton(onClick = { parsed?.let { text = formatBpm((it + 1).coerceAtMost(400.0)) } }) { Text("+", fontSize = 20.sp) }
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onSet) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Split / Duplicate / Delete for the selected clip, floating over the arrangement. */
@Composable
fun ClipActionBar(canSplit: Boolean, onSplit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionItem(Icons.Filled.ContentCut, "Split", canSplit, onSplit)
            ActionItem(Icons.Filled.ContentCopy, "Duplicate", true, onDuplicate)
            ActionItem(Icons.Filled.Delete, "Delete", true, onDelete)
        }
    }
}

@Composable
private fun ActionItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (enabled) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.4f))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.4f))
    }
}

/** Short status line (take saved, export done, errors), floating over the arrangement. */
@Composable
fun StatusPill(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onDismiss),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 3.dp,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * Mic level as a bar over −60…0 dBFS: green, amber above −6 dB, red (for the
 * rest of the take) once anything clipped, since a clipped take can't be
 * fixed afterwards.
 */
@Composable
fun InputLevelMeter(peakMilli: Int, clipped: Boolean, modifier: Modifier = Modifier) {
    val peak = peakMilli / 1000f
    val db = if (peak > 0f) 20f * log10(peak) else -60f
    val fraction = ((db + 60f) / 60f).coerceIn(0f, 1f)
    val barColor = when {
        clipped -> MaterialTheme.colorScheme.error
        db > -6f -> MeterAmber
        else -> MeterGreen
    }
    Box(modifier = modifier.clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(barColor))
    }
}

/** "0:07" — a call-timer style elapsed time. */
fun formatElapsed(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * Shown over the lyrics while a take keeps rolling with the Scratchpad
 * hidden, so leaving to look at the lyrics never looks like it stopped the
 * take. Tap to come back.
 */
@Composable
fun MinimizedRecordingBubble(elapsedText: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError,
        shadowElevation = 6.dp,
    ) {
        Row(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(elapsedText, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Text("Recording — tap to view", style = MaterialTheme.typography.labelMedium)
        }
    }
}
