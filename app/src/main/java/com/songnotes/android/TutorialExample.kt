package com.songnotes.android

import com.songnotes.core.audio.MultitrackClipSpec
import com.songnotes.core.audio.MultitrackTrackSpec
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

private const val kSampleRate = 48000

/**
 * A small example demo for the Scratchpad tours, so "tap a clip" or "drag
 * the tabs at its edges" has a clip to point at on a song with nothing
 * recorded yet. Generated in code (a hummed-sounding melody and a bass line
 * under it) rather than shipped as a file: a few seconds of audio, built
 * once, and never saved into the song. The tour shows it in place of an
 * empty project and it disappears when the tour ends.
 */
object TutorialExample {
    val tracks: List<MultitrackTrackSpec> by lazy {
        listOf(
            MultitrackTrackSpec(
                clips = listOf(MultitrackClipSpec(buffer = melody(), startFrame = 0L)),
                name = "Example: melody",
                colorIndex = 0,
            ),
            MultitrackTrackSpec(
                clips = listOf(MultitrackClipSpec(buffer = bass(), startFrame = 2L * kSampleRate)),
                name = "Example: bass",
                colorIndex = 3,
            ),
        )
    }

    /** MIDI note → Hz. */
    private fun hz(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private fun melody(): FloatArray {
        // C E G E | F A G – , half a second a note, at a singable level.
        val notes = listOf(60, 64, 67, 64, 65, 69, 67, 67)
        return render(notes.map { hz(it) to 0.5 }, amplitude = 0.45f, brightness = 0.35)
    }

    private fun bass(): FloatArray {
        val notes = listOf(36, 36, 41, 43)
        return render(notes.map { hz(it) to 1.0 }, amplitude = 0.35f, brightness = 0.15)
    }

    /** Notes as (frequency, seconds), each a soft sine with a little second harmonic and a gentle swell and fade. */
    private fun render(notes: List<Pair<Double, Double>>, amplitude: Float, brightness: Double): FloatArray {
        val total = notes.sumOf { (it.second * kSampleRate).toInt() }
        val out = FloatArray(total)
        var at = 0
        for ((freq, seconds) in notes) {
            val n = (seconds * kSampleRate).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / kSampleRate
                val attack = 1.0 - exp(-t * 30.0)
                val release = ((n - i).toDouble() / (0.08 * kSampleRate)).coerceAtMost(1.0)
                val swell = 0.85 + 0.15 * sin(2 * PI * 5.0 * t) // a touch of vibrato-like movement
                val tone = sin(2 * PI * freq * t) + brightness * sin(2 * PI * 2 * freq * t)
                out[at + i] = (amplitude * attack * release * swell * tone / (1 + brightness)).toFloat()
            }
            at += n
        }
        return out
    }
}
