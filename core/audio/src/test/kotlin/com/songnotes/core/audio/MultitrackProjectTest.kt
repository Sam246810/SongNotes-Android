package com.songnotes.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Only exercises the methods that don't construct or call a real
// AudioEngine (see build.gradle.kts's testImplementation comment for why:
// AudioEngine's companion object loads a native library that doesn't
// exist in a plain JVM unit test process). withPunchIn/play/armOverdub/
// exportToWav all need an actual engine and are verified on-device
// instead, same as everything else in Phase 4 that touches JNI.
class MultitrackProjectTest {

    private fun clip(startFrame: Long, lengthFrames: Long) =
        MultitrackClipSpec(buffer = FloatArray(lengthFrames.toInt()), startFrame = startFrame)

    @Test
    fun `empty project has zero total frames`() {
        assertEquals(0L, MultitrackProject().totalFrames)
    }

    @Test
    fun `addTrack appends without mutating the original`() {
        val original = MultitrackProject()
        val withTrack = original.addTrack(MultitrackTrackSpec(clips = listOf(clip(0, 10))))
        assertTrue(original.tracks.isEmpty()) // immutability: original untouched
        assertEquals(1, withTrack.tracks.size)
    }

    @Test
    fun `addTrack with no argument appends an empty track`() {
        val project = MultitrackProject().addTrack()
        assertEquals(1, project.tracks.size)
        assertTrue(project.tracks[0].clips.isEmpty())
    }

    @Test
    fun `removeTrack drops only the targeted index`() {
        val project = MultitrackProject()
            .addTrack(MultitrackTrackSpec(clips = listOf(clip(0, 1))))
            .addTrack(MultitrackTrackSpec(clips = listOf(clip(0, 2))))
            .addTrack(MultitrackTrackSpec(clips = listOf(clip(0, 3))))
        val afterRemoval = project.removeTrack(1)
        assertEquals(2, afterRemoval.tracks.size)
        assertEquals(1L, afterRemoval.tracks[0].clips[0].lengthFrames)
        assertEquals(3L, afterRemoval.tracks[1].clips[0].lengthFrames)
    }

    @Test
    fun `withTrackGain only changes the targeted track`() {
        val project = MultitrackProject()
            .addTrack(MultitrackTrackSpec(clips = emptyList(), gain = 1.0f))
            .addTrack(MultitrackTrackSpec(clips = emptyList(), gain = 1.0f))
        val updated = project.withTrackGain(1, 0.5f)
        assertEquals(1.0f, updated.tracks[0].gain)
        assertEquals(0.5f, updated.tracks[1].gain)
    }

    @Test
    fun `withTrackMuted and withTrackSoloed toggle independently`() {
        val project = MultitrackProject().addTrack(MultitrackTrackSpec(clips = emptyList()))
        val muted = project.withTrackMuted(0, true)
        assertTrue(muted.tracks[0].muted)
        assertFalse(muted.tracks[0].soloed)
        val soloed = muted.withTrackSoloed(0, true)
        assertTrue(soloed.tracks[0].muted) // unaffected by the solo toggle
        assertTrue(soloed.tracks[0].soloed)
    }

    @Test
    fun `totalFrames is the furthest clip end across every track`() {
        val project = MultitrackProject()
            .addTrack(MultitrackTrackSpec(clips = listOf(clip(startFrame = 0, lengthFrames = 100))))
            .addTrack(MultitrackTrackSpec(clips = listOf(clip(startFrame = 50, lengthFrames = 200))))
            .addTrack(MultitrackTrackSpec(clips = listOf(clip(startFrame = 10, lengthFrames = 5))))
        assertEquals(250L, project.totalFrames) // track 2: 50 + 200
    }

    @Test
    fun `totalFrames ignores mute -- a muted track still determines duration`() {
        // Matches the engine's own startMultitrackPlayback/armRecording
        // totalFrames computation, which deliberately ignores mute/solo —
        // see docs/handoff/PHASE-04.md.
        val project = MultitrackProject()
            .addTrack(MultitrackTrackSpec(clips = listOf(clip(0, 10))))
            .addTrack(MultitrackTrackSpec(clips = listOf(clip(0, 1000)), muted = true))
        assertEquals(1000L, project.totalFrames)
    }
}
