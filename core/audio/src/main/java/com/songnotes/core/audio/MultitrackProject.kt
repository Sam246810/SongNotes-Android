package com.songnotes.core.audio

/**
 * The current in-memory "song" — the single authoritative list of tracks
 * a punch-in recording flow reads from and writes back to, replacing what
 * every Phase 4 diagnostics section did before this: build its own
 * throwaway `List<MultitrackTrackSpec>` inline, with nowhere to persist
 * (even within a session) whatever got recorded.
 *
 * Immutable — every mutating method returns a NEW `MultitrackProject`
 * rather than changing this one in place, the same reducer-style pattern
 * [EngineState] and [EngineCapabilities] already use for engine-observed
 * state. Callers hold the current value in whatever state container fits
 * (a `mutableStateOf<MultitrackProject>` in Compose, a plain `var` in a
 * test) and reassign it after each mutation.
 *
 * Deliberately lives in `:core:audio`, not `:core:domain` — this is not
 * the JVM reference mixer's data model (`:core:domain`'s `Clip`/`Track`
 * exist ONLY for cross-validating the mixing math independently, see
 * `ClipMixer.kt`'s own doc comment) and is not pure business logic:
 * punch-in recording is inherently a real-time engine operation
 * ([AudioEngine.armRecording]/[AudioEngine.punchIn]), so this project
 * model is tied to [AudioEngine]'s own JNI-facing types
 * ([MultitrackTrackSpec]/[MultitrackClipSpec]) rather than reinventing an
 * independent one that would just need converting back and forth.
 *
 * Splicing itself is never reimplemented here — [withPunchIn] always
 * calls [AudioEngine.punchIn] (the one, C++-backed implementation), same
 * reasoning as everywhere else in Phase 4.
 */
data class MultitrackProject(val tracks: List<MultitrackTrackSpec> = emptyList()) {

    /** The furthest clip end across every track — what a full playback/export run would cover. */
    val totalFrames: Long
        get() = tracks.maxOfOrNull { track ->
            track.clips.maxOfOrNull { it.startFrame + it.lengthFrames } ?: 0L
        } ?: 0L

    /** Appends a new track (empty by default — a fresh target for a punch-in take). */
    fun addTrack(track: MultitrackTrackSpec = MultitrackTrackSpec(clips = emptyList())): MultitrackProject =
        copy(tracks = tracks + track)

    fun removeTrack(index: Int): MultitrackProject =
        copy(tracks = tracks.filterIndexed { i, _ -> i != index })

    fun withTrackGain(index: Int, gain: Float): MultitrackProject = updateTrack(index) { it.copy(gain = gain) }
    fun withTrackMuted(index: Int, muted: Boolean): MultitrackProject = updateTrack(index) { it.copy(muted = muted) }
    fun withTrackSoloed(index: Int, soloed: Boolean): MultitrackProject =
        updateTrack(index) { it.copy(soloed = soloed) }

    private fun updateTrack(index: Int, transform: (MultitrackTrackSpec) -> MultitrackTrackSpec): MultitrackProject =
        copy(tracks = tracks.mapIndexed { i, t -> if (i == index) transform(t) else t })

    /**
     * Applies [transform] to a single clip — the timeline's drag (moves
     * [MultitrackClipSpec.startFrame]) and trim (moves
     * [MultitrackClipSpec.bufferOffsetFrames]/[MultitrackClipSpec.lengthFrames])
     * gestures both go through this one generic hook rather than dedicated
     * "move" and "trim" methods, so a drag that crosses into trimming
     * territory (dragging a clip's left edge) can update startFrame and the
     * trim window together as one atomic change instead of two separate
     * project mutations.
     */
    fun withClipTransform(
        trackIndex: Int,
        clipIndex: Int,
        transform: (MultitrackClipSpec) -> MultitrackClipSpec,
    ): MultitrackProject = updateTrack(trackIndex) { track ->
        track.copy(clips = track.clips.mapIndexed { i, c -> if (i == clipIndex) transform(c) else c })
    }

    /**
     * Splices [newClip] into track [index]'s existing clip list via
     * [AudioEngine.punchIn] and returns the resulting project. The one
     * method here that touches the engine for something other than
     * transport (recording/playback) — still stateless on the engine's
     * side (see [AudioEngine.punchIn]'s own doc comment), so this is safe
     * to call at any time, not just mid-session.
     */
    fun withPunchIn(engine: AudioEngine, index: Int, newClip: MultitrackClipSpec): MultitrackProject {
        val splicedClips = engine.punchIn(tracks[index].clips, newClip)
        return updateTrack(index) { it.copy(clips = splicedClips) }
    }

    /** Plays every track in this project via [AudioEngine.startMultitrackPlayback]. */
    fun play(engine: AudioEngine): Boolean = engine.startMultitrackPlayback(tracks)

    /**
     * Arms a real overdub recording onto [targetIndex] — every OTHER
     * track in this project becomes an audible backing track (see
     * [AudioEngine.armRecording]'s `backingTracks` param), synced so the
     * new take lands at [backingTracksStartFrame] on this project's shared
     * timeline. Does NOT itself commit the resulting take: call
     * [withPunchIn] with the take's samples once recording stops and the
     * file's been read back into memory — same manual sequence the
     * "Overdub + punch-in, end to end" diagnostics flow already proved
     * works, just no longer needing that call site to hand-build track
     * lists itself.
     */
    fun armOverdub(
        engine: AudioEngine,
        filePath: String,
        bpm: Double,
        beatsPerBar: Int,
        countInBeats: Int,
        targetIndex: Int,
        backingTracksStartFrame: Long = 0L,
        calibrationOffsetFrames: Double = 0.0,
    ): Boolean {
        val backingTracks = tracks.filterIndexed { i, _ -> i != targetIndex }
        return engine.armRecording(
            filePath, bpm, beatsPerBar, countInBeats, calibrationOffsetFrames, backingTracks,
            backingTracksStartFrame,
        )
    }

    /** Exports this project's full mixdown via [AudioEngine.exportMixdownToWav]. */
    fun exportToWav(engine: AudioEngine, filePath: String, sampleRate: Int): Boolean =
        engine.exportMixdownToWav(filePath, tracks, sampleRate)
}
