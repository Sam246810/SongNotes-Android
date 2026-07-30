package com.songnotes.core.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

private fun fullClip(buffer: FloatArray, startFrame: Long) =
    Clip(buffer = buffer, startFrame = startFrame, bufferOffsetFrames = 0L, lengthFrames = buffer.size.toLong())

class ClipMixerTest {

    @Test
    fun `single track single clip passes through`() {
        val track = Track(clips = listOf(fullClip(floatArrayOf(1f, 2f, 3f), 0)))
        val mixed = mixTracks(listOf(track), 0, 3)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), mixed, 0f)
    }

    @Test
    fun `multiple tracks sum`() {
        val a = Track(clips = listOf(fullClip(floatArrayOf(1f, 1f), 0)))
        val b = Track(clips = listOf(fullClip(floatArrayOf(0.5f, 0.5f), 0)))
        val mixed = mixTracks(listOf(a, b), 0, 2)
        assertArrayEquals(floatArrayOf(1.5f, 1.5f), mixed, 0f)
    }

    @Test
    fun `overlapping clips within a track sum`() {
        val track = Track(
            clips = listOf(
                fullClip(floatArrayOf(1f, 1f, 1f), 0),
                fullClip(floatArrayOf(2f, 2f), 1), // overlaps frames 1-2
            ),
        )
        val mixed = mixTracks(listOf(track), 0, 3)
        assertArrayEquals(floatArrayOf(1f, 3f, 3f), mixed, 0f)
    }

    @Test
    fun `gain scales the track`() {
        val track = Track(clips = listOf(fullClip(floatArrayOf(2f, 4f), 0)), gain = 0.5f)
        val mixed = mixTracks(listOf(track), 0, 2)
        assertArrayEquals(floatArrayOf(1f, 2f), mixed, 0f)
    }

    @Test
    fun `muted track contributes nothing`() {
        val track = Track(clips = listOf(fullClip(floatArrayOf(5f, 5f), 0)), muted = true)
        val mixed = mixTracks(listOf(track), 0, 2)
        assertArrayEquals(floatArrayOf(0f, 0f), mixed, 0f)
    }

    @Test
    fun `solo silences non-soloed tracks`() {
        val a = Track(clips = listOf(fullClip(floatArrayOf(1f), 0)), soloed = true)
        val b = Track(clips = listOf(fullClip(floatArrayOf(1f), 0))) // not soloed, not muted
        val mixed = mixTracks(listOf(a, b), 0, 1)
        assertArrayEquals(floatArrayOf(1f), mixed, 0f) // only a's contribution
    }

    @Test
    fun `solo overrides the soloed track's own mute`() {
        val track = Track(clips = listOf(fullClip(floatArrayOf(7f), 0)), soloed = true, muted = true)
        val mixed = mixTracks(listOf(track), 0, 1)
        assertArrayEquals(floatArrayOf(7f), mixed, 0f)
    }

    @Test
    fun `requested window narrower than clip returns only that slice`() {
        val track = Track(clips = listOf(fullClip(floatArrayOf(1f, 2f, 3f, 4f, 5f), 0)))
        val mixed = mixTracks(listOf(track), 2, 4)
        assertArrayEquals(floatArrayOf(3f, 4f), mixed, 0f)
    }

    @Test
    fun `chunked mixing matches whole-buffer mixing`() {
        // The same property test_track_mixer.cpp's
        // ChunkedMixingMatchesWholeBufferMixing pins down on the C++ side —
        // callback-sized chunked mixing must agree with one whole-range
        // call, or real-time playback and offline mixdown could disagree.
        val a = Track(clips = listOf(fullClip(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f), 3)), gain = 0.7f)
        val b = Track(
            clips = listOf(
                fullClip(floatArrayOf(-1f, -2f, -3f, -4f, -5f), 0),
                fullClip(floatArrayOf(0.5f, 0.5f, 0.5f), 10),
            ),
        )
        val tracks = listOf(a, b)
        val totalFrames = 20L

        val wholeBuffer = mixTracks(tracks, 0, totalFrames)

        val chunked = mutableListOf<Float>()
        val chunkSize = 3L // deliberately doesn't divide totalFrames evenly
        var start = 0L
        while (start < totalFrames) {
            val end = minOf(start + chunkSize, totalFrames)
            chunked.addAll(mixTracks(tracks, start, end).toList())
            start += chunkSize
        }

        assertArrayEquals(wholeBuffer, chunked.toFloatArray(), 0f)
    }

    @Test
    fun `mixing an already-spliced clip list reads quiet-loud-quiet`() {
        // Doesn't exercise punch-in splicing itself (that has exactly one
        // implementation, in C++ — see ClipMixer.kt's own doc comment) —
        // just proves the mixer correctly sums a clip list shaped the way
        // punch-in's output looks: a loud clip inserted into the middle of
        // a quiet one.
        val quiet = 1.0f
        val loud = 9.0f
        val track = Track(
            clips = listOf(
                Clip(buffer = FloatArray(3) { quiet }, startFrame = 0),
                Clip(buffer = FloatArray(3) { loud }, startFrame = 3),
                Clip(buffer = FloatArray(3) { quiet }, startFrame = 6),
            ),
        )
        val mixed = mixTracks(listOf(track), 0, 9)
        for (i in 0 until 3) assertEquals(quiet, mixed[i], 0f)
        for (i in 3 until 6) assertEquals(loud, mixed[i], 0f)
        for (i in 6 until 9) assertEquals(quiet, mixed[i], 0f)
    }

    @Test
    fun `empty tracks list produces silence`() {
        val mixed = mixTracks(emptyList(), 0, 4)
        assertArrayEquals(FloatArray(4), mixed, 0f)
    }

    @Test
    fun `frames past the end of buffer contribute nothing`() {
        // lengthFrames longer than the underlying buffer -- shouldn't
        // happen from real callers, but the mixer must not crash or read
        // out of bounds; matches dsp::mixTracksInto's own bufIdx guard.
        val track = Track(clips = listOf(Clip(buffer = floatArrayOf(1f, 2f), startFrame = 0, lengthFrames = 5)))
        val mixed = mixTracks(listOf(track), 0, 5)
        assertArrayEquals(floatArrayOf(1f, 2f, 0f, 0f, 0f), mixed, 0f)
    }
}
