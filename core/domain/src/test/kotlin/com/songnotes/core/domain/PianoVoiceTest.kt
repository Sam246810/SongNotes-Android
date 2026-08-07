package com.songnotes.core.domain

import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PianoVoiceTest {

    @Test
    fun `PIANO_SAMPLES has 29 entries spaced a minor third apart from C1 to C8`() {
        assertEquals(29, PIANO_SAMPLES.size)
        assertEquals(24, PIANO_SAMPLES.first().midi)
        assertEquals(108, PIANO_SAMPLES.last().midi)
        for (i in 1 until PIANO_SAMPLES.size) {
            assertEquals(3, PIANO_SAMPLES[i].midi - PIANO_SAMPLES[i - 1].midi)
        }
    }

    @Test
    fun `nearestSampleFor finds an exact hit`() {
        assertEquals(60, nearestSampleFor(60).midi) // C4 is a real sample
    }

    @Test
    fun `nearestSampleFor snaps to the nearer sample, ties broken toward the lower one`() {
        // 24..27 gap: 25 is 1 away from 24 and 2 away from 27 -> 24.
        assertEquals(24, nearestSampleFor(25).midi)
        // 26 is 2 away from 24 and 1 away from 27 -> 27.
        assertEquals(27, nearestSampleFor(26).midi)
    }

    @Test
    fun `nearestSampleFor clamps out-of-range midi to the nearest edge sample`() {
        assertEquals(24, nearestSampleFor(0).midi)
        assertEquals(108, nearestSampleFor(127).midi)
    }

    @Test
    fun `nearestSampleFor never picks a sample farther than 1 semitone away within the recorded range`() {
        // Within [24, 108] (the recorded grid itself) the 3-semitone spacing
        // guarantees a nearest neighbor at most 1 semitone away. Outside that
        // range there's nothing to interpolate between, so distance grows
        // unbounded with how far out-of-range the request is -- expected, not
        // a bug (see the separate out-of-range clamping test above).
        for (midi in 24..108) {
            val sample = nearestSampleFor(midi)
            assertTrue("midi=$midi picked ${sample.midi}, too far", abs(sample.midi - midi) <= 1)
        }
    }

    @Test
    fun `playbackRateFor is 1point0 for an exact sample hit at matching sample rates`() {
        val sample = nearestSampleFor(60)
        val rate = playbackRateFor(60, sample, sampleFileHz = 44100.0, engineSampleRateHz = 44100.0)
        assertEquals(1.0, rate, 1e-9)
    }

    @Test
    fun `playbackRateFor applies the semitone ratio matching the web app's formula`() {
        val sample = nearestSampleFor(61) // nearest is C4 (60), 1 semitone up
        assertEquals(60, sample.midi)
        val rate = playbackRateFor(61, sample, sampleFileHz = 44100.0, engineSampleRateHz = 44100.0)
        val expected = 2.0.pow(1.0 / 12.0)
        assertEquals(expected, rate, 1e-9)
    }

    @Test
    fun `playbackRateFor folds in the sample-rate correction the web app never needs`() {
        val sample = nearestSampleFor(60)
        // Sample recorded at 44.1kHz, engine running at 48kHz -- omitting this
        // factor is exactly the silent "every note ~9percent sharp" bug this
        // test exists to prevent.
        val rate = playbackRateFor(60, sample, sampleFileHz = 44100.0, engineSampleRateHz = 48000.0)
        val expected = 44100.0 / 48000.0
        assertEquals(expected, rate, 1e-9)
    }

    @Test
    fun `envelopeAt starts at 0 and reaches 1point0 at the end of the attack`() {
        assertEquals(0.0, envelopeAt(0.0), 1e-9)
        assertEquals(1.0, envelopeAt(0.005), 1e-6)
    }

    @Test
    fun `envelopeAt decays to the documented checkpoints`() {
        assertEquals(0.25, envelopeAt(0.8), 1e-6)
        assertEquals(0.001, envelopeAt(4.0), 1e-6)
        assertEquals(0.001, envelopeAt(10.0), 1e-9) // holds the floor forever after
    }

    @Test
    fun `envelopeAt is monotonically non-increasing after the attack while held`() {
        var previous = envelopeAt(0.005)
        var t = 0.005
        while (t < 5.0) {
            t += 0.05
            val current = envelopeAt(t)
            assertTrue("envelope rose from $previous to $current at t=$t", current <= previous + 1e-9)
            previous = current
        }
    }

    @Test
    fun `envelopeAt release ramps from the true value at release time, not from 1point0`() {
        val releaseAge = 1.0 // well past the attack/first decay stage
        val heldValueAtRelease = envelopeAt(releaseAge)
        // Right at the instant of release, the released envelope must equal the held one.
        assertEquals(heldValueAtRelease, envelopeAt(releaseAge, releaseAge), 1e-9)
        // Immediately after, it decays toward the floor, not back up toward 1.0.
        val justAfter = envelopeAt(releaseAge + 0.05, releaseAge)
        assertTrue(justAfter < heldValueAtRelease)
        assertTrue(justAfter > 0.001)
        // Fully released after RELEASE_SECONDS (0.4s).
        assertEquals(0.001, envelopeAt(releaseAge + 0.4, releaseAge), 1e-6)
        assertEquals(0.001, envelopeAt(releaseAge + 5.0, releaseAge), 1e-9)
    }

    @Test
    fun `envelopeAt before release time is unaffected by a later release`() {
        val releaseAge = 2.0
        assertEquals(envelopeAt(0.5), envelopeAt(0.5, releaseAge), 1e-9)
    }

    @Test
    fun `envelopeAt handles a release at the very first sample without throwing`() {
        val value = envelopeAt(0.0, 0.0)
        assertTrue(value in 0.0..1.0)
        assertEquals(0.001, envelopeAt(0.4, 0.0), 1e-6)
    }

    @Test
    fun `interpolatedSample matches a hand-computed linear interpolation`() {
        val buffer = floatArrayOf(0.0f, 10.0f, 20.0f, 30.0f)
        assertEquals(0.0f, interpolatedSample(buffer, 0.0)!!, 1e-6f)
        assertEquals(5.0f, interpolatedSample(buffer, 0.5)!!, 1e-6f)
        assertEquals(10.0f, interpolatedSample(buffer, 1.0)!!, 1e-6f)
        assertEquals(23.0f, interpolatedSample(buffer, 2.3)!!, 1e-6f)
    }

    @Test
    fun `interpolatedSample returns null once it can no longer form a pair`() {
        val buffer = floatArrayOf(1.0f, 2.0f, 3.0f)
        assertNotNull(interpolatedSample(buffer, 1.9))
        assertNull(interpolatedSample(buffer, 2.0)) // idx=2 has no idx+1
        assertNull(interpolatedSample(buffer, 5.0))
        assertNull(interpolatedSample(buffer, -1.0))
    }

    @Test
    fun `renderVoiceInto sums into the output buffer rather than overwriting it`() {
        val buffer = FloatArray(1000) { 1.0f } // constant 1.0 source, so envelope is the only shaping factor
        val out = FloatArray(4) { 5.0f } // pre-filled, as if another voice already rendered here
        renderVoiceInto(
            out = out, numFrames = 4, buffer = buffer, startReadPos = 0.0, rate = 1.0,
            startAgeSeconds = 10.0, releaseAgeSeconds = null, sampleRateHz = 48000.0, gain = 1.0f,
        )
        // Age 10s is well past the decay floor (0.001), so each frame added ~0.001.
        for (v in out) assertTrue("expected slightly above the pre-filled 5.0, got $v", v > 5.0f && v < 5.01f)
    }

    @Test
    fun `renderVoiceInto reports exhausted and stops advancing once the buffer runs out`() {
        val buffer = FloatArray(10) { 1.0f }
        val out = FloatArray(20)
        val result = renderVoiceInto(
            out = out, numFrames = 20, buffer = buffer, startReadPos = 0.0, rate = 1.0,
            startAgeSeconds = 1.0, releaseAgeSeconds = null, sampleRateHz = 48000.0, gain = 1.0f,
        )
        assertTrue(result.exhausted)
        assertTrue(result.nextReadPos < 10.0)
    }

    @Test
    fun `renderVoiceInto advances readPos by rate per frame`() {
        val buffer = FloatArray(1000) { it.toFloat() }
        val out = FloatArray(10)
        val result = renderVoiceInto(
            out = out, numFrames = 10, buffer = buffer, startReadPos = 5.0, rate = 2.0,
            startAgeSeconds = 100.0, releaseAgeSeconds = null, sampleRateHz = 48000.0, gain = 1.0f,
        )
        assertEquals(25.0, result.nextReadPos, 1e-9)
        assertTrue(!result.exhausted)
    }
}
