package com.songnotes.core.audio

import kotlin.math.roundToLong

/**
 * The arrangement view's scale and grid, as plain math so it can be unit
 * tested and so the live drag preview and the committed edit are computed by
 * exactly the same code (an earlier timeline previewed one thing and
 * committed another, which is what made a dragged clip jump on release).
 *
 * Scale is "pixels per beat", fixed by the zoom level and independent of how
 * long the project is — committing an edit never rescales the view, unlike
 * the old "whole project fits the screen" timeline.
 */
object ArrangementMath {

    fun framesPerBeat(bpm: Double, sampleRate: Int): Double = sampleRate * 60.0 / bpm

    /**
     * Grid unit in beats for the current zoom, Ableton's adaptive grid: the
     * finest of bar multiples → bar → beat → 1/2 … 1/128 of a beat whose lines
     * are at least [minSpacingPx] apart. Zoomed far in it reaches 1/128 beat
     * (a few milliseconds), which is effectively free placement.
     */
    fun gridUnitBeats(pxPerBeat: Double, beatsPerBar: Int, minSpacingPx: Double): Double {
        val bar = beatsPerBar.coerceAtLeast(1).toDouble()
        val candidates = buildList {
            var sub = 1.0 / 128.0
            while (sub < 1.0) { add(sub); sub *= 2.0 }
            add(1.0)
            var bars = 1.0
            while (bars <= 1024.0) { add(bar * bars); bars *= 2.0 }
        }
        return candidates.firstOrNull { it * pxPerBeat >= minSpacingPx } ?: candidates.last()
    }

    /**
     * How many bars between ruler labels so numbers never overlap: the
     * smallest power of two whose spacing is at least [minLabelPx]. Returns
     * 1 when individual bars are already far enough apart.
     */
    fun rulerLabelStepBars(pxPerBeat: Double, beatsPerBar: Int, minLabelPx: Double): Int {
        val pxPerBar = pxPerBeat * beatsPerBar.coerceAtLeast(1)
        var step = 1
        while (step * pxPerBar < minLabelPx && step < (1 shl 20)) step *= 2
        return step
    }

    /** Nearest multiple of [unitFrames] (no-op when [unitFrames] <= 0, i.e. snap off). */
    fun snap(frame: Long, unitFrames: Double): Long =
        if (unitFrames <= 0.0) frame else ((frame / unitFrames).roundToLong() * unitFrames).roundToLong()

    /** Move: the clip's start snaps; it can't go before frame 0. */
    fun moved(clip: MultitrackClipSpec, deltaFrames: Long, unitFrames: Double): MultitrackClipSpec =
        clip.copy(startFrame = snap(clip.startFrame + deltaFrames, unitFrames).coerceAtLeast(0L))

    /**
     * Left-edge trim: the edge snaps, the audio under it stays put in time
     * (start and buffer offset move together), bounded by the start of the
     * recorded audio, frame 0, and a minimum length of [minFrames].
     */
    fun trimmedLeft(clip: MultitrackClipSpec, deltaFrames: Long, unitFrames: Double, minFrames: Long = 1L): MultitrackClipSpec {
        val end = clip.startFrame + clip.lengthFrames
        val earliest = maxOf(clip.startFrame - clip.bufferOffsetFrames, 0L)
        val newStart = snap(clip.startFrame + deltaFrames, unitFrames).coerceIn(earliest, end - minFrames)
        val shift = newStart - clip.startFrame
        return clip.copy(
            startFrame = newStart,
            bufferOffsetFrames = clip.bufferOffsetFrames + shift,
            lengthFrames = clip.lengthFrames - shift,
        )
    }

    /** Right-edge trim: the end snaps, bounded by the recorded audio's end and [minFrames]. */
    fun trimmedRight(clip: MultitrackClipSpec, deltaFrames: Long, unitFrames: Double, minFrames: Long = 1L): MultitrackClipSpec {
        val maxEnd = clip.startFrame + (clip.buffer.size.toLong() - clip.bufferOffsetFrames)
        val newEnd = snap(clip.startFrame + clip.lengthFrames + deltaFrames, unitFrames)
            .coerceIn(clip.startFrame + minFrames, maxEnd)
        return clip.copy(lengthFrames = newEnd - clip.startFrame)
    }
}
