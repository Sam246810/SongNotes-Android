package com.songnotes.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrangementMathTest {

    private fun clip(start: Long, length: Long, offset: Long = 0L, bufferSize: Int = (offset + length).toInt()) =
        MultitrackClipSpec(FloatArray(bufferSize), startFrame = start, bufferOffsetFrames = offset, lengthFrames = length)

    @Test
    fun `grid gets finer as you zoom in and reaches sub-millisecond-ish units`() {
        val coarse = ArrangementMath.gridUnitBeats(pxPerBeat = 5.0, beatsPerBar = 4, minSpacingPx = 30.0)
        val beat = ArrangementMath.gridUnitBeats(pxPerBeat = 40.0, beatsPerBar = 4, minSpacingPx = 30.0)
        val fine = ArrangementMath.gridUnitBeats(pxPerBeat = 4000.0, beatsPerBar = 4, minSpacingPx = 30.0)
        assertEquals(8.0, coarse, 0.0) // 2 bars of 4/4 at 5 px/beat = 40 px
        assertEquals(1.0, beat, 0.0)
        assertEquals(1.0 / 128.0, fine, 0.0)
    }

    @Test
    fun `ruler labels thin out so they never get closer than the minimum`() {
        assertEquals(1, ArrangementMath.rulerLabelStepBars(pxPerBeat = 20.0, beatsPerBar = 4, minLabelPx = 60.0))
        assertEquals(4, ArrangementMath.rulerLabelStepBars(pxPerBeat = 5.0, beatsPerBar = 4, minLabelPx = 60.0))
        for (pxPerBeat in listOf(0.1, 0.7, 3.3, 9.0, 50.0)) {
            val step = ArrangementMath.rulerLabelStepBars(pxPerBeat, 4, 60.0)
            assertTrue(step * pxPerBeat * 4 >= 60.0)
        }
    }

    @Test
    fun `snap rounds to the nearest unit and is a no-op when off`() {
        assertEquals(1000L, ArrangementMath.snap(1040L, 500.0))
        assertEquals(1500L, ArrangementMath.snap(1260L, 500.0))
        assertEquals(1040L, ArrangementMath.snap(1040L, 0.0))
    }

    @Test
    fun `moving left stops at frame zero instead of overshooting`() {
        val moved = ArrangementMath.moved(clip(start = 300, length = 100), deltaFrames = -1000, unitFrames = 0.0)
        assertEquals(0L, moved.startFrame)
        assertEquals(100L, moved.lengthFrames)
    }

    @Test
    fun `left trim keeps the audio fixed in time and can't go past the recorded start`() {
        val c = clip(start = 1000, length = 500, offset = 200, bufferSize = 1000)
        val shorter = ArrangementMath.trimmedLeft(c, deltaFrames = 100, unitFrames = 0.0)
        assertEquals(1100L, shorter.startFrame)
        assertEquals(300L, shorter.bufferOffsetFrames)
        assertEquals(400L, shorter.lengthFrames)
        // Audio at project frame 1200 is buffer frame 400 before and after.
        assertEquals(1200L - c.startFrame + c.bufferOffsetFrames, 1200L - shorter.startFrame + shorter.bufferOffsetFrames)

        val revealed = ArrangementMath.trimmedLeft(c, deltaFrames = -10_000, unitFrames = 0.0)
        assertEquals(800L, revealed.startFrame) // only 200 frames of audio exist before the old edge
        assertEquals(0L, revealed.bufferOffsetFrames)
        assertEquals(700L, revealed.lengthFrames)
    }

    @Test
    fun `right trim is bounded by the recorded audio and a minimum length`() {
        val c = clip(start = 0, length = 500, offset = 0, bufferSize = 800)
        assertEquals(800L, ArrangementMath.trimmedRight(c, 10_000, 0.0).lengthFrames)
        assertEquals(1L, ArrangementMath.trimmedRight(c, -10_000, 0.0).lengthFrames)
    }

    @Test
    fun `split makes two clips that share the buffer and cover the original exactly`() {
        val c = clip(start = 100, length = 400, offset = 50, bufferSize = 600)
        val project = MultitrackProject(tracks = listOf(MultitrackTrackSpec(clips = listOf(c))))
        val (head, tail) = project.splitClip(0, 0, atFrame = 250).tracks[0].clips
        assertSame(c.buffer, head.buffer)
        assertSame(c.buffer, tail.buffer)
        assertEquals(100L to 150L, head.startFrame to head.lengthFrames)
        assertEquals(250L, tail.startFrame)
        assertEquals(200L, tail.bufferOffsetFrames)
        assertEquals(250L, tail.lengthFrames)
        // A split point on an edge or outside the clip changes nothing.
        assertEquals(project, project.splitClip(0, 0, atFrame = 100))
        assertEquals(project, project.splitClip(0, 0, atFrame = 9999))
    }

    @Test
    fun `tracks get Ableton-style names and distinct colours, and move as a unit`() {
        val p = MultitrackProject().addTrack().addTrack().addTrack()
        assertEquals(listOf("1 Audio", "2 Audio", "3 Audio"), p.tracks.map { it.name })
        assertEquals(listOf(0, 1, 2), p.tracks.map { it.colorIndex })
        val moved = p.moveTrack(from = 0, to = 2)
        assertEquals(listOf("2 Audio", "3 Audio", "1 Audio"), moved.tracks.map { it.name })
        assertEquals(listOf(1, 2, 0), moved.tracks.map { it.colorIndex })
        // After a delete the next new track doesn't reuse a name still in use.
        assertEquals("4 Audio", p.removeTrack(0).addTrack().tracks.last().name) // "3 Audio" is still taken
    }

    @Test
    fun `delete removes only the targeted clip`() {
        val p = MultitrackProject(tracks = listOf(MultitrackTrackSpec(clips = listOf(clip(0, 10), clip(20, 10)))))
        assertEquals(listOf(20L), p.deleteClip(0, 0).tracks[0].clips.map { it.startFrame })
    }
}
