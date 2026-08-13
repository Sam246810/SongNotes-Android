package com.songnotes.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.MultitrackClipSpec
import com.songnotes.core.audio.MultitrackTrackSpec
import kotlin.math.roundToLong

private const val kSampleRate = 48000

private val PlayheadColor = Color(0xFFF87171)
private val ScrubColor = Color(0xFF60A5FA)
private val TrimHandleColor = Color(0xFFB45309)
private val TrackRowHeight = 56.dp
private val TrackRowSpacing = 4.dp
private val TrimHandleWidth = 12.dp
private val MinClipWidth = 8.dp
private val RulerHeight = 20.dp

/**
 * Fixed, non-theme palette (distinct from [PlayheadColor]'s red and
 * [ScrubColor]'s light blue, which stay reserved for those two markers) so a
 * track's identity — waveform here, the matching swatch on its [TrackRow] in
 * [ScratchpadScreen] — reads the same regardless of light/dark theme.
 */
private val TrackPalette = listOf(
    Color(0xFF6750A4), // purple
    Color(0xFF00796B), // teal
    Color(0xFFEF6C00), // orange
    Color(0xFF1565C0), // blue
    Color(0xFF2E7D32), // green
    Color(0xFFAD1457), // pink
)

/** Stable per-track color, cycling through [TrackPalette] for track counts beyond its size. */
fun trackColor(trackIndex: Int): Color = TrackPalette[trackIndex % TrackPalette.size]

/** No-op default for [Timeline]'s edit callback — read-only callers don't need to pass one. */
private val NoOpClipChange: (Int, Int, (MultitrackClipSpec) -> MultitrackClipSpec) -> Unit = { _, _, _ -> }

/**
 * Project timeline: a tappable ruler above one horizontal row per track,
 * each clip drawn as a [Waveform] positioned and sized against
 * [totalFrames]. A single position marker line spans the ruler and every
 * track row: red at [playbackFrame] while something is playing, otherwise
 * blue at [scrubFrame] — the project frame the NEXT punch-in recording
 * will land at (see [ScrubRuler] and `ScratchpadScreen.beginRecording`,
 * which passes this same frame as `armOverdub`'s `backingTracksStartFrame`
 * so the backing tracks and the new take agree on where "now" is).
 * Peak pyramids are built once per clip buffer, remembered keyed on the
 * buffer array's identity, so scrolling/recomposing this screen doesn't
 * re-run [AudioEngine.buildPeakPyramid] for clips that haven't changed.
 *
 * A beat/bar grid is drawn over every track row from [bpm]/[beatsPerBar] —
 * a bar-start line (beat index 0 modulo [beatsPerBar]) heavier than a
 * regular beat line, the same downbeat-vs-regular distinction the actual
 * metronome click makes. Purely visual: this is what "the changes [to time
 * signature] should reflect in the grid" means, not a separate scheduling
 * source — the real scheduling is `audio_engine.cpp`'s live click drain,
 * which reads the same [beatsPerBar] value via [MultitrackProject].
 *
 * When [enabled], each clip supports two touch gestures, both live-previewed
 * locally during the drag and only committed to [onClipChange] on release
 * (same "don't call back on every pixel of drag" reasoning as the gain
 * slider elsewhere in this screen):
 *  - Dragging the middle of a clip moves it (`startFrame`) along the shared
 *    timeline.
 *  - Dragging the narrow handle at either edge trims that end — the left
 *    handle moves `bufferOffsetFrames`/`startFrame` together (so the
 *    untrimmed audio's timeline position doesn't jump) and shrinks
 *    `lengthFrames`; the right handle only changes `lengthFrames`.
 */
@Composable
fun Timeline(
    engine: AudioEngine,
    tracks: List<MultitrackTrackSpec>,
    totalFrames: Long,
    bpm: Double,
    beatsPerBar: Int,
    playbackFrame: Long?,
    scrubFrame: Long = 0L,
    onScrubChange: (Long) -> Unit = {},
    enabled: Boolean = true,
    onClipChange: (trackIndex: Int, clipIndex: Int, transform: (MultitrackClipSpec) -> MultitrackClipSpec) -> Unit = NoOpClipChange,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty() || totalFrames <= 0L) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val beatGridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        val barGridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

        Column {
            ScrubRuler(
                totalFrames = totalFrames,
                scrubFrame = scrubFrame,
                widthPx = widthPx,
                enabled = enabled,
                onScrubChange = onScrubChange,
            )
            Spacer(Modifier.height(TrackRowSpacing))

            Box {
                Column(verticalArrangement = Arrangement.spacedBy(TrackRowSpacing)) {
                    tracks.forEachIndexed { trackIndex, track ->
                        TimelineTrackRow(
                            engine = engine,
                            trackIndex = trackIndex,
                            track = track,
                            totalFrames = totalFrames,
                            widthPx = widthPx,
                            enabled = enabled,
                            onClipChange = onClipChange,
                        )
                    }
                }

                val rowsHeight = TrackRowHeight * tracks.size + TrackRowSpacing * (tracks.size - 1)

                if (bpm > 0.0) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(rowsHeight)) {
                        drawBeatGrid(totalFrames, bpm, beatsPerBar, beatGridColor, barGridColor)
                    }
                }

                val markerFrame = playbackFrame ?: scrubFrame
                val markerColor = if (playbackFrame != null) PlayheadColor else ScrubColor
                val fraction = (markerFrame.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .offset { IntOffset((widthPx * fraction).toInt(), 0) }
                        .width(2.dp)
                        .height(rowsHeight)
                        .background(markerColor),
                )
            }
        }
    }
}

/**
 * Vertical line at every beat (bar-start lines heavier), from frame 0 —
 * mirrors `dsp::renderClickTrack`'s own scheduling exactly:
 * `beatIntervalFrames = round(sampleRate * 60 / bpm)`, bar start at
 * `beatIndex % beatsPerBar == 0`. `bpm <= 0` is handled by the caller
 * (skips drawing entirely, same as the DSP side rendering silence).
 */
private fun DrawScope.drawBeatGrid(
    totalFrames: Long,
    bpm: Double,
    beatsPerBar: Int,
    beatColor: Color,
    barColor: Color,
) {
    val beatIntervalFrames = (kSampleRate * 60.0 / bpm).roundToLong()
    if (beatIntervalFrames <= 0) return
    val safeBeatsPerBar = beatsPerBar.coerceAtLeast(1)
    val widthPx = size.width

    var beatFrame = 0L
    var beatIndex = 0
    while (beatFrame < totalFrames) {
        val x = widthPx * beatFrame.toFloat() / totalFrames.toFloat()
        val isBarStart = beatIndex % safeBeatsPerBar == 0
        drawLine(
            color = if (isBarStart) barColor else beatColor,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = if (isBarStart) 2f else 1f,
        )
        beatFrame += beatIntervalFrames
        beatIndex++
    }
}

/**
 * Thin tappable strip above the track rows — tapping anywhere sets
 * [onScrubChange] to the frame under the tap, clamped to `[0, totalFrames]`.
 * Kept as its own gesture surface (rather than layering a tap detector onto
 * the track rows themselves) so it never has to arbitrate against a clip's
 * own drag/trim gestures underneath the same touch point.
 */
@Composable
private fun ScrubRuler(
    totalFrames: Long,
    scrubFrame: Long,
    widthPx: Float,
    enabled: Boolean,
    onScrubChange: (Long) -> Unit,
) {
    val framesPerPx = totalFrames.toFloat() / widthPx
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RulerHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (enabled) {
                    Modifier.pointerInput(totalFrames) {
                        detectTapGestures(
                            onTap = { offset ->
                                onScrubChange((offset.x * framesPerPx).toLong().coerceIn(0L, totalFrames))
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val fraction = (scrubFrame.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .offset { IntOffset((widthPx * fraction).toInt() - 4, 0) }
                .width(8.dp)
                .fillMaxHeight()
                .background(ScrubColor),
        )
    }
}

@Composable
private fun TimelineTrackRow(
    engine: AudioEngine,
    trackIndex: Int,
    track: MultitrackTrackSpec,
    totalFrames: Long,
    widthPx: Float,
    enabled: Boolean,
    onClipChange: (Int, Int, (MultitrackClipSpec) -> MultitrackClipSpec) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackRowHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        track.clips.forEachIndexed { clipIndex, clip ->
            TimelineClip(
                engine = engine,
                trackIndex = trackIndex,
                clipIndex = clipIndex,
                clip = clip,
                totalFrames = totalFrames,
                widthPx = widthPx,
                enabled = enabled,
                onClipChange = onClipChange,
            )
        }
    }
}

@Composable
private fun TimelineClip(
    engine: AudioEngine,
    trackIndex: Int,
    clipIndex: Int,
    clip: MultitrackClipSpec,
    totalFrames: Long,
    widthPx: Float,
    enabled: Boolean,
    onClipChange: (Int, Int, (MultitrackClipSpec) -> MultitrackClipSpec) -> Unit,
) {
    val pyramid = remember(clip.buffer) { engine.buildPeakPyramid(clip.buffer) }
    val density = LocalDensity.current
    val framesPerPx = totalFrames.toFloat() / widthPx

    // Live drag/trim previews, in pixels — reset to 0 (via the `clip` remember
    // key) once the committed clip this composable is showing actually changes.
    var dragPx by remember(clip) { mutableStateOf(0f) }
    var leftTrimPx by remember(clip) { mutableStateOf(0f) }
    var rightTrimPx by remember(clip) { mutableStateOf(0f) }

    val minWidthPx = with(density) { MinClipWidth.toPx() }
    val baseXPx = widthPx * clip.startFrame.toFloat() / totalFrames.toFloat()
    val baseWidthPx = widthPx * clip.lengthFrames.toFloat() / totalFrames.toFloat()
    val xPx = baseXPx + dragPx + leftTrimPx
    val clipWidthPx = (baseWidthPx - leftTrimPx + rightTrimPx).coerceAtLeast(minWidthPx)

    Box(
        modifier = Modifier
            .offset { IntOffset(xPx.toInt(), 0) }
            .width(with(density) { clipWidthPx.toDp() })
            .fillMaxHeight(),
    ) {
        Waveform(
            pyramid = pyramid,
            bufferOffsetFrames = clip.bufferOffsetFrames,
            lengthFrames = clip.lengthFrames,
            color = trackColor(trackIndex),
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (enabled) {
                        Modifier.pointerInput(clip) {
                            detectDragGestures(
                                onDrag = { change, dragAmount -> change.consume(); dragPx += dragAmount.x },
                                onDragEnd = {
                                    val deltaFrames = (dragPx * framesPerPx).toLong()
                                    dragPx = 0f
                                    onClipChange(trackIndex, clipIndex) { c ->
                                        c.copy(startFrame = (c.startFrame + deltaFrames).coerceAtLeast(0L))
                                    }
                                },
                                onDragCancel = { dragPx = 0f },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        )

        if (enabled) {
            TrimHandle(
                modifier = Modifier.align(Alignment.CenterStart),
                onDrag = { leftTrimPx += it },
                onDragEnd = {
                    val deltaFrames = (leftTrimPx * framesPerPx).toLong()
                    leftTrimPx = 0f
                    onClipChange(trackIndex, clipIndex) { c ->
                        val maxDelta = c.lengthFrames - 1L
                        val clampedDelta = deltaFrames.coerceIn(-c.bufferOffsetFrames, maxDelta)
                        c.copy(
                            startFrame = (c.startFrame + clampedDelta).coerceAtLeast(0L),
                            bufferOffsetFrames = c.bufferOffsetFrames + clampedDelta,
                            lengthFrames = c.lengthFrames - clampedDelta,
                        )
                    }
                },
                onDragCancel = { leftTrimPx = 0f },
            )
            TrimHandle(
                modifier = Modifier.align(Alignment.CenterEnd),
                onDrag = { rightTrimPx += it },
                onDragEnd = {
                    val deltaFrames = (rightTrimPx * framesPerPx).toLong()
                    rightTrimPx = 0f
                    onClipChange(trackIndex, clipIndex) { c ->
                        val maxLength = c.buffer.size.toLong() - c.bufferOffsetFrames
                        c.copy(lengthFrames = (c.lengthFrames + deltaFrames).coerceIn(1L, maxLength))
                    }
                },
                onDragCancel = { rightTrimPx = 0f },
            )
        }
    }
}

@Composable
private fun TrimHandle(
    modifier: Modifier,
    onDrag: (deltaPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    Box(
        modifier = modifier
            .width(TrimHandleWidth)
            .fillMaxHeight()
            .background(TrimHandleColor)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount -> change.consume(); onDrag(dragAmount.x) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                )
            },
    )
}
