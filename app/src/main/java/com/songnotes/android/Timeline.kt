package com.songnotes.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.songnotes.core.audio.AudioEngine
import com.songnotes.core.audio.MultitrackClipSpec
import com.songnotes.core.audio.MultitrackTrackSpec

private val ClipColor = Color(0xFF6EE7B7)
private val PlayheadColor = Color(0xFFF87171)
private val TrackRowHeight = 56.dp
private val TrackRowSpacing = 4.dp

/**
 * Read-only project timeline: one horizontal row per track, each clip drawn
 * as a [Waveform] positioned and sized against [totalFrames], with a
 * playhead line overlaid at [playbackFrame] (null hides it — nothing is
 * playing). Peak pyramids are built once per clip buffer, remembered keyed
 * on the buffer array's identity, so scrolling/recomposing this screen
 * doesn't re-run [AudioEngine.buildPeakPyramid] for clips that haven't
 * changed.
 *
 * Purely visual for now — touch clip drag/trim and tap-to-scrub are later
 * Phase 10 slices (see docs/PLAN.md).
 */
@Composable
fun Timeline(
    engine: AudioEngine,
    tracks: List<MultitrackTrackSpec>,
    totalFrames: Long,
    playbackFrame: Long?,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty() || totalFrames <= 0L) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }

        Box {
            Column(verticalArrangement = Arrangement.spacedBy(TrackRowSpacing)) {
                tracks.forEach { track ->
                    TimelineTrackRow(engine = engine, track = track, totalFrames = totalFrames, widthPx = widthPx)
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
    track: MultitrackTrackSpec,
    totalFrames: Long,
    widthPx: Float,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(TrackRowHeight)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        track.clips.forEach { clip ->
            TimelineClip(engine = engine, clip = clip, totalFrames = totalFrames, widthPx = widthPx)
        }
    }
}

@Composable
private fun TimelineClip(
    engine: AudioEngine,
    clip: MultitrackClipSpec,
    totalFrames: Long,
    widthPx: Float,
) {
    val pyramid = remember(clip.buffer) { engine.buildPeakPyramid(clip.buffer) }
    val density = LocalDensity.current
    val xPx = widthPx * clip.startFrame.toFloat() / totalFrames.toFloat()
    val clipWidthPx = (widthPx * clip.lengthFrames.toFloat() / totalFrames.toFloat()).coerceAtLeast(1f)

    Waveform(
        pyramid = pyramid,
        bufferOffsetFrames = clip.bufferOffsetFrames,
        lengthFrames = clip.lengthFrames,
        color = ClipColor,
        modifier = Modifier
            .offset { IntOffset(xPx.toInt(), 0) }
            .width(with(density) { clipWidthPx.toDp() })
            .fillMaxHeight(),
    )
}
