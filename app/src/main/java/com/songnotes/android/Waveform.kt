package com.songnotes.android

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.songnotes.core.audio.PeakLevel
import com.songnotes.core.audio.PeakPyramid

/**
 * Renders a clip's min/max waveform from a pre-built [PeakPyramid] (built
 * once via [com.songnotes.core.audio.AudioEngine.buildPeakPyramid] when a
 * clip's buffer is finalized, not on every recomposition — this composable
 * only draws). [bufferOffsetFrames]/[lengthFrames] mirror the same-named
 * fields on `MultitrackClipSpec`: a clip can be a trimmed *view* into the
 * pyramid's underlying buffer, so the visible peak range and each peak's
 * on-screen width are both computed relative to that trim window, not the
 * full buffer.
 *
 * [PeakPyramid.selectLevelForZoom] picks the coarsest level whose
 * `samplesPerPeak` still fits the canvas's actual pixel width, so this never
 * draws more peaks than there are pixels for regardless of how far the
 * timeline is zoomed in.
 */
@Composable
fun Waveform(
    pyramid: PeakPyramid?,
    bufferOffsetFrames: Long,
    lengthFrames: Long,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (pyramid == null || pyramid.levels.isEmpty() || lengthFrames <= 0L) return@Canvas
        val samplesPerPixel = lengthFrames.toDouble() / size.width.coerceAtLeast(1f)
        val level = pyramid.selectLevelForZoom(samplesPerPixel)
        if (level.peakCount == 0) return@Canvas
        drawWaveformLevel(level, bufferOffsetFrames, lengthFrames, color)
    }
}

private fun DrawScope.drawWaveformLevel(
    level: PeakLevel,
    bufferOffsetFrames: Long,
    lengthFrames: Long,
    color: Color,
) {
    val widthPx = size.width
    val midY = size.height / 2f
    val samplesPerPeak = level.samplesPerPeak.toLong()
    val trimEndFrame = bufferOffsetFrames + lengthFrames

    val firstPeak = (bufferOffsetFrames / samplesPerPeak).toInt().coerceIn(0, level.peakCount - 1)
    val lastPeak = ((trimEndFrame - 1) / samplesPerPeak).toInt().coerceIn(firstPeak, level.peakCount - 1)

    for (i in firstPeak..lastPeak) {
        val peakStartFrame = i.toLong() * samplesPerPeak
        val peakEndFrame = peakStartFrame + samplesPerPeak
        val xStartFrame = (peakStartFrame - bufferOffsetFrames).coerceAtLeast(0L)
        val xEndFrame = (peakEndFrame - bufferOffsetFrames).coerceAtMost(lengthFrames)
        if (xEndFrame <= xStartFrame) continue

        val x = widthPx * xStartFrame.toFloat() / lengthFrames.toFloat()
        val xEnd = widthPx * xEndFrame.toFloat() / lengthFrames.toFloat()
        val barWidth = (xEnd - x).coerceAtLeast(1f)

        val min = level.mins[i].coerceIn(-1f, 1f)
        val max = level.maxes[i].coerceIn(-1f, 1f)
        val yTop = midY - max * midY
        val yBottom = midY - min * midY

        drawRect(
            color = color,
            topLeft = Offset(x, yTop),
            size = Size(barWidth, (yBottom - yTop).coerceAtLeast(1f)),
        )
    }
}
