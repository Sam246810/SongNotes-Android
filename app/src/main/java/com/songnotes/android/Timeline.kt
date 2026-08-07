package com.songnotes.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.MultitrackClipSpec
import com.songnotes.core.audio.MultitrackTrackSpec

private val ClipColor = Color(0xFF6EE7B7)
private val PlayheadColor = Color(0xFFF87171)
private val TrimHandleColor = Color(0xFFB45309)
private val TrackRowHeight = 56.dp
private val TrackRowSpacing = 4.dp
private val TrimHandleWidth = 12.dp
private val MinClipWidth = 8.dp

/** No-op default for [Timeline]'s edit callback — read-only callers don't need to pass one. */
private val NoOpClipChange: (Int, Int, (MultitrackClipSpec) -> MultitrackClipSpec) -> Unit = { _, _, _ -> }

/**
 * Project timeline: one horizontal row per track, each clip drawn as a
 * [Waveform] positioned and sized against [totalFrames], with a playhead
 * line overlaid at [playbackFrame] (null hides it — nothing is playing).
 * Peak pyramids are built once per clip buffer, remembered keyed on the
 * buffer array's identity, so scrolling/recomposing this screen doesn't
 * re-run [AudioEngine.buildPeakPyramid] for clips that haven't changed.
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
    playbackFrame: Long?,
    enabled: Boolean = true,
    onClipChange: (trackIndex: Int, clipIndex: Int, transform: (MultitrackClipSpec) -> MultitrackClipSpec) -> Unit = NoOpClipChange,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty() || totalFrames <= 0L) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }

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

            if (playbackFrame != null) {
                val fraction = (playbackFrame.toFloat() / totalFrames.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .offset { IntOffset((widthPx * fraction).toInt(), 0) }
                        .width(2.dp)
                        .height(TrackRowHeight * tracks.size + TrackRowSpacing * (tracks.size - 1))
                        .background(PlayheadColor),
                )
            }
        }
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
            .clip(RoundedCornerShape(4.dp))
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
            color = ClipColor,
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
