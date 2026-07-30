package com.songnotes.core.domain

/**
 * One audio region placed on a track's timeline — the JVM-side
 * counterpart to `dsp::Clip` in `track_mixer.h`, but a genuinely
 * independent implementation: this module and the C++ mixer are two
 * separate expressions of the same algorithm description, cross-validated
 * against each other rather than one being a translation of the other.
 * See docs/handoff/PHASE-04.md.
 *
 * [buffer]: the full underlying mono f32 source audio, never mutated.
 * [startFrame]: where this clip begins on the TRACK's shared timeline
 * (frame 0 of the track, not of [buffer]).
 * [bufferOffsetFrames]: where within [buffer] playback starts.
 * [lengthFrames]: how many frames actually play, starting at
 * [bufferOffsetFrames] — may be less than `buffer.size` after trimming.
 */
data class Clip(
    val buffer: FloatArray,
    val startFrame: Long,
    val bufferOffsetFrames: Long = 0L,
    val lengthFrames: Long = buffer.size.toLong() - bufferOffsetFrames,
)

data class Track(
    val clips: List<Clip>,
    val gain: Float = 1.0f,
    val muted: Boolean = false,
    val soloed: Boolean = false,
)

/**
 * Mixes [tracks] into a `FloatArray` covering exactly
 * `[startFrameInclusive, endFrameExclusive)` — the JVM reference mixer
 * for Phase 4's own cross-validation Done criterion ("exported WAV is
 * sample-identical to a JVM reference mixer given the same clip list").
 *
 * Deliberately follows the same iteration order as `dsp::mixTracksInto`
 * (tracks outer, clips inner, frame-by-frame accumulation) — not because
 * that's the only correct order, but because IEEE 754 addition isn't
 * associative, so a differently-ordered (but equally valid) summation
 * could disagree with the C++ mixer in the last bit for reasons that have
 * nothing to do with either implementation being wrong. Matching iteration
 * order is what makes exact equality a meaningful comparison instead of
 * one that needs an epsilon tolerance to paper over.
 *
 * Solo semantics — a judgment call, not specified by the plan, and MUST
 * match `dsp::mixTracksInto`'s own documented choice exactly or every
 * solo+mute case disagrees even when the core summing logic is fine: if
 * ANY track is soloed, only soloed tracks play, and a soloed track's own
 * [Track.muted] is ignored while any solo is active.
 *
 * No output clipping/limiting — same as the C++ version, deliberately, so
 * summed samples outside [-1, 1] pass through unclamped on both sides
 * equally.
 */
fun mixTracks(tracks: List<Track>, startFrameInclusive: Long, endFrameExclusive: Long): FloatArray {
    val length = (endFrameExclusive - startFrameInclusive).coerceAtLeast(0L).toInt()
    val out = FloatArray(length)
    if (length == 0) return out

    val anySoloed = tracks.any { it.soloed }
    for (track in tracks) {
        val audible = if (anySoloed) track.soloed else !track.muted
        if (!audible) continue
        for (clip in track.clips) {
            if (clip.lengthFrames <= 0) continue
            val clipEnd = clip.startFrame + clip.lengthFrames
            val overlapStart = maxOf(startFrameInclusive, clip.startFrame)
            val overlapEnd = minOf(endFrameExclusive, clipEnd)
            if (overlapStart >= overlapEnd) continue
            for (frame in overlapStart until overlapEnd) {
                val outIdx = (frame - startFrameInclusive).toInt()
                val framesIntoClip = frame - clip.startFrame
                val bufIdx = (clip.bufferOffsetFrames + framesIntoClip).toInt()
                if (bufIdx >= clip.buffer.size) continue
                out[outIdx] += clip.buffer[bufIdx] * track.gain
            }
        }
    }
    return out
}
