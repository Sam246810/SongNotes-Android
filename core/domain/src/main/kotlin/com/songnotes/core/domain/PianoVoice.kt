package com.songnotes.core.domain

import kotlin.math.abs
import kotlin.math.pow

/**
 * Ported from the web app's `src/components/PianoPanel/PianoPanel.jsx` —
 * Salamander Grand Piano, velocity 13, 29 recorded notes spaced a minor
 * third (3 semitones) apart from C1 (MIDI 24) to C8 (MIDI 108). Every note
 * off that grid is played by pitch-shifting the nearest recorded sample, so
 * the maximum stretch is +/-1 semitone.
 *
 * This file has no Android/JNI dependency on purpose -- it's the pure,
 * host-testable reference (`PianoVoiceTest.kt`) for the same math
 * `cpp/dsp/piano_voice.{h,cpp}` implements for the real-time engine,
 * mirroring the `ClipMixer.kt`/`dsp::mixTracksInto` split from Phase 4
 * (docs/handoff/PHASE-04.md): write the tricky math once in pure Kotlin,
 * where it can be tested in seconds with no device, then port it to C++
 * and cross-validate the two agree exactly on a real device.
 *
 * Where this deliberately diverges from the web version (documented, not
 * accidental):
 * - The web app plays through Web Audio directly against the *device's*
 *   audio context sample rate; nothing there ever needs a sample-rate
 *   correction term because the browser resamples for you. Android decodes
 *   each mp3 once into a fixed-rate buffer and plays it through an engine
 *   that may run at a different rate — [playbackRateFor] has to fold that
 *   ratio in explicitly, or every note comes out detuned by however far the
 *   sample's native rate is from the engine's.
 * - Polyphony: the web version has no voice cap (the browser handles it).
 *   The real-time engine uses a fixed-size voice pool (see
 *   `cpp/audio_engine.h`) — a genuine, documented Android-side constraint,
 *   not modeled here since voice stealing is a policy decision for the
 *   engine, not part of this file's pure per-voice math.
 */

/** One recorded sample: its note name (for reference/debugging) and MIDI number. */
data class PianoSample(val note: String, val midi: Int)

/**
 * The 29 recorded notes, in ascending MIDI order — single source of truth
 * for which notes have a real sample. Android bundles the corresponding
 * audio as `assets/piano/{midi}.mp3` (see `PianoSampleLoader.kt` in
 * `:core:audio`); this table doesn't know or care about file naming, only
 * about the note grid itself.
 */
val PIANO_SAMPLES: List<PianoSample> = listOf(
    PianoSample("C1", 24), PianoSample("D#1", 27), PianoSample("F#1", 30), PianoSample("A1", 33),
    PianoSample("C2", 36), PianoSample("D#2", 39), PianoSample("F#2", 42), PianoSample("A2", 45),
    PianoSample("C3", 48), PianoSample("D#3", 51), PianoSample("F#3", 54), PianoSample("A3", 57),
    PianoSample("C4", 60), PianoSample("D#4", 63), PianoSample("F#4", 66), PianoSample("A4", 69),
    PianoSample("C5", 72), PianoSample("D#5", 75), PianoSample("F#5", 78), PianoSample("A5", 81),
    PianoSample("C6", 84), PianoSample("D#6", 87), PianoSample("F#6", 90), PianoSample("A6", 93),
    PianoSample("C7", 96), PianoSample("D#7", 99), PianoSample("F#7", 102), PianoSample("A7", 105),
    PianoSample("C8", 108),
)

/**
 * The recorded sample closest to [midi] — ties broken toward the lower
 * sample, matching the web version's linear `dist < minDist` scan (a
 * later equal-distance candidate never replaces an earlier one). In
 * practice this table's odd 3-semitone spacing means an exact integer-MIDI
 * tie can never actually occur, but the tie-break rule is still real
 * behavior worth locking down, not an unreachable branch.
 */
fun nearestSampleFor(midi: Int): PianoSample =
    PIANO_SAMPLES.minByOrNull { abs(it.midi - midi) } ?: error("PIANO_SAMPLES must never be empty")

/**
 * The combined rate to read [sample]'s buffer at so [midi] sounds at the
 * right pitch, played back through an engine running at
 * [engineSampleRateHz] from a buffer decoded at [sampleFileHz]. The
 * semitone term matches the web version's `Math.pow(2, diff / 12)`
 * exactly; the sample-rate term has no web equivalent (see this file's
 * own doc comment) and is what keeps a 44.1kHz sample in tune on an
 * engine running at 48kHz.
 */
fun playbackRateFor(midi: Int, sample: PianoSample, sampleFileHz: Double, engineSampleRateHz: Double): Double {
    val semitoneRatio = 2.0.pow((midi - sample.midi) / 12.0)
    val sampleRateRatio = sampleFileHz / engineSampleRateHz
    return semitoneRatio * sampleRateRatio
}

private const val ATTACK_SECONDS = 0.005
private const val DECAY1_SECONDS = 0.8
private const val DECAY1_VALUE = 0.25
private const val DECAY2_SECONDS = 4.0
private const val DECAY2_VALUE = 0.001
private const val RELEASE_SECONDS = 0.4
private const val FLOOR = 0.001

/** Geometric (exponential) interpolation between (t0,v0) and (t1,v1), matching Web Audio's `exponentialRampToValueAtTime`. */
private fun expInterp(v0: Double, v1: Double, t0: Double, t1: Double, t: Double): Double {
    if (t1 <= t0) return v1
    val frac = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
    return v0 * (v1 / v0).pow(frac)
}

/** The envelope's value at [ageSeconds] if the note is still held (never released) — see [envelopeAt]. */
private fun heldEnvelope(ageSeconds: Double): Double = when {
    ageSeconds <= 0.0 -> 0.0
    ageSeconds < ATTACK_SECONDS -> ageSeconds / ATTACK_SECONDS
    ageSeconds < DECAY1_SECONDS -> expInterp(1.0, DECAY1_VALUE, ATTACK_SECONDS, DECAY1_SECONDS, ageSeconds)
    ageSeconds < DECAY2_SECONDS -> expInterp(DECAY1_VALUE, DECAY2_VALUE, DECAY1_SECONDS, DECAY2_SECONDS, ageSeconds)
    else -> DECAY2_VALUE
}

/**
 * The voice's gain multiplier at [ageSeconds] since note-on, given an
 * optional [releaseAgeSeconds] (the age at which note-off happened, or
 * null if still held). Ported from `PianoPanel.jsx`'s Web Audio gain
 * schedule: linear attack to 1.0 over 5ms, exponential decay to 0.25 by
 * 800ms, exponential decay to 0.001 by 4s (the natural one-shot-sample
 * envelope, `triggerNoteOn`), and on release, an exponential ramp from
 * whatever the gain actually was at that instant down to 0.001 over 400ms
 * (`triggerNoteOff`'s `cancelScheduledValues` + `setValueAtTime(current)` +
 * `exponentialRampToValueAtTime`) -- release always ramps from the true
 * current value, not from 1.0, so releasing during the attack or decay
 * ramps sounds natural rather than snapping.
 *
 * [heldEnvelope] can only return exactly 0.0 at age 0 (the note's very
 * first sample); releasing at that exact instant is coerced to a tiny
 * epsilon before the exponential release ramp, since geometric
 * interpolation from a true zero is undefined (mirrors the fact that Web
 * Audio's own `exponentialRampToValueAtTime` throws on a zero starting
 * value) -- an edge case the original code doesn't hit in practice either.
 */
fun envelopeAt(ageSeconds: Double, releaseAgeSeconds: Double? = null): Double {
    if (releaseAgeSeconds == null || ageSeconds <= releaseAgeSeconds) return heldEnvelope(ageSeconds)
    val heldAtRelease = heldEnvelope(releaseAgeSeconds).coerceAtLeast(1e-6)
    val sinceRelease = ageSeconds - releaseAgeSeconds
    return if (sinceRelease < RELEASE_SECONDS) {
        expInterp(heldAtRelease, FLOOR, 0.0, RELEASE_SECONDS, sinceRelease)
    } else {
        FLOOR
    }
}

/** Linearly-interpolated sample at fractional index [readPos] into [buffer]. `null` once [readPos] runs past the last interpolatable pair. */
fun interpolatedSample(buffer: FloatArray, readPos: Double): Float? {
    val idx = readPos.toInt()
    if (idx < 0 || idx + 1 >= buffer.size) return null
    val frac = (readPos - idx).toFloat()
    val s0 = buffer[idx]
    val s1 = buffer[idx + 1]
    return s0 + (s1 - s0) * frac
}

/** [renderVoiceInto]'s result: where this voice's read cursor ended up, and whether it ran off the end of its sample buffer. */
data class VoiceRenderResult(val nextReadPos: Double, val exhausted: Boolean)

/**
 * Renders one voice's next [numFrames] into [out] (mono), summing —
 * never overwriting — so multiple voices layer via repeated calls against
 * the same buffer. [startAgeSeconds] is the voice's age (seconds since
 * note-on) at the first frame of this call; age advances one sample period
 * (`1 / sampleRateHz`) per frame rendered, independent of [rate] (age
 * tracks wall-clock time the voice has been sounding, not how far its read
 * cursor has moved through the source buffer). Stops early — reporting
 * [VoiceRenderResult.exhausted] — the moment [readPos] runs past the last
 * interpolatable sample; the caller is responsible for deactivating the
 * voice when that happens (any un-rendered frames in [out] are simply left
 * as whatever they already held, i.e. silence contributed).
 */
fun renderVoiceInto(
    out: FloatArray,
    numFrames: Int,
    buffer: FloatArray,
    startReadPos: Double,
    rate: Double,
    startAgeSeconds: Double,
    releaseAgeSeconds: Double?,
    sampleRateHz: Double,
    gain: Float,
): VoiceRenderResult {
    var readPos = startReadPos
    for (frame in 0 until numFrames) {
        val sample = interpolatedSample(buffer, readPos) ?: return VoiceRenderResult(readPos, true)
        val ageSeconds = startAgeSeconds + frame / sampleRateHz
        val env = envelopeAt(ageSeconds, releaseAgeSeconds)
        out[frame] += sample * env.toFloat() * gain
        readPos += rate
    }
    return VoiceRenderResult(readPos, false)
}
