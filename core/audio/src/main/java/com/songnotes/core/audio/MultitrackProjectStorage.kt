package com.songnotes.core.audio

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists a [MultitrackProject] to disk: one `manifest.json` describing
 * the track/clip structure (`gain`/`muted`/`soloed`, and per-clip
 * `startFrame`/`bufferOffsetFrames`/`lengthFrames`), plus one raw `.f32`
 * file per clip holding its actual audio samples — the same raw-float32
 * format every take/export in this codebase already uses, so nothing new
 * to decode. `org.json` (built into Android, no extra dependency) for the
 * manifest rather than pulling in a serialization library for one small
 * document. Matches [CalibrationStore]'s own "small scoped storage class,
 * no front-loaded data layer" precedent — just applied to bigger (audio)
 * data instead of a few key-value pairs.
 *
 * A single fixed project directory for now
 * (`context.filesDir/scratchpad/`) — there's no multi-project management
 * UI yet (see `docs/handoff/PHASE-10.md`'s "What's left"), so "the
 * scratchpad" is the only project this needs to hold. Multi-project
 * support would mean parameterizing this by project name/id, not a
 * different storage mechanism.
 */
class MultitrackProjectStorage(context: Context) {
    private val projectDir = File(context.filesDir, "scratchpad")
    private val clipsDir = File(projectDir, "clips")
    private val manifestFile = File(projectDir, "manifest.json")

    /** True if a saved project exists on disk. Cheap — doesn't read or parse it. */
    fun exists(): Boolean = manifestFile.exists()

    /**
     * Writes [project] to disk, replacing whatever was saved before.
     * Deletes stale clip files from a previous save first — a clip
     * removed (or a track removed) since the last save shouldn't leave an
     * orphaned `.f32` file behind forever. Not RT-safe (file I/O) and not
     * cheap for a large project (writes every clip's full audio again
     * every call, even unchanged ones) — call from a background
     * dispatcher, same as [AudioEngine.exportMixdownToWav]'s own callers
     * already do.
     */
    fun save(project: MultitrackProject) {
        clipsDir.mkdirs()
        clipsDir.listFiles()?.forEach { it.delete() }

        val tracksJson = JSONArray()
        var clipIndex = 0
        for (track in project.tracks) {
            val clipsJson = JSONArray()
            for (clip in track.clips) {
                val fileName = "clip_${clipIndex++}.f32"
                writeF32(File(clipsDir, fileName), clip.buffer)
                clipsJson.put(
                    JSONObject()
                        .put("file", fileName)
                        .put("startFrame", clip.startFrame)
                        .put("bufferOffsetFrames", clip.bufferOffsetFrames)
                        .put("lengthFrames", clip.lengthFrames),
                )
            }
            tracksJson.put(
                JSONObject()
                    .put("gain", track.gain.toDouble())
                    .put("muted", track.muted)
                    .put("soloed", track.soloed)
                    .put("clips", clipsJson),
            )
        }
        manifestFile.writeText(JSONObject().put("tracks", tracksJson).toString())
    }

    /** Reads the saved project back, or null if [exists] would return false. */
    fun load(): MultitrackProject? {
        if (!manifestFile.exists()) return null
        val root = JSONObject(manifestFile.readText())
        val tracksJson = root.getJSONArray("tracks")
        val tracks = (0 until tracksJson.length()).map { i ->
            val trackJson = tracksJson.getJSONObject(i)
            val clipsJson = trackJson.getJSONArray("clips")
            val clips = (0 until clipsJson.length()).map { c ->
                val clipJson = clipsJson.getJSONObject(c)
                MultitrackClipSpec(
                    buffer = readF32(File(clipsDir, clipJson.getString("file"))),
                    startFrame = clipJson.getLong("startFrame"),
                    bufferOffsetFrames = clipJson.getLong("bufferOffsetFrames"),
                    lengthFrames = clipJson.getLong("lengthFrames"),
                )
            }
            MultitrackTrackSpec(
                clips = clips,
                gain = trackJson.getDouble("gain").toFloat(),
                muted = trackJson.getBoolean("muted"),
                soloed = trackJson.getBoolean("soloed"),
            )
        }
        return MultitrackProject(tracks)
    }

    /** Deletes the saved project (manifest + every clip file) entirely. */
    fun clear() {
        projectDir.deleteRecursively()
    }

    private fun writeF32(file: File, samples: FloatArray) {
        val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(samples)
        file.writeBytes(buffer.array())
    }

    private fun readF32(file: File): FloatArray {
        val bytes = file.readBytes()
        val samples = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(samples)
        return samples
    }
}
