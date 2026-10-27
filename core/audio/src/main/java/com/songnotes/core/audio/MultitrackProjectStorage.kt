package com.songnotes.core.audio

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists a [MultitrackProject] to disk: one `manifest.json` describing
 * the track/clip structure (`gain`/`muted`/`soloed`, and per-clip
 * `file`/`startFrame`/`bufferOffsetFrames`/`lengthFrames`), plus one raw
 * `.f32` file per distinct audio buffer — the same raw-float32 format
 * every take/export in this codebase already uses, so nothing new to
 * decode. `org.json` (built into Android, no extra dependency) for the
 * manifest rather than pulling in a serialization library for one small
 * document. Matches [CalibrationStore]'s own "small scoped storage class,
 * no front-loaded data layer" precedent — just applied to bigger (audio)
 * data instead of a few key-value pairs.
 *
 * One project directory per song (`context.filesDir/scratchpad/<songId>/`)
 * — every song gets its own scratchpad session rather than the whole app
 * sharing a single one, so opening the scratchpad from song A never shows
 * (or, worse, silently overwrites) a take recorded from song B. `songId`
 * is always a `UUID.randomUUID()` string (see `Song.id`'s callers in
 * `:core:data`), so it's already filesystem-safe as a bare directory name
 * with no sanitizing needed.
 *
 * **Incremental.** Audio files are keyed by buffer *identity*: a
 * `FloatArray` this instance has already written (or loaded) is never
 * written again, so a gain/mute/solo change or a clip drag writes only the
 * manifest. Several clips can reference one file — a take split into a
 * head and tail by a punch-in shares one buffer (see [AudioEngine.punchIn])
 * and is stored once. This relies on the instance living as long as the
 * editing session (`ScratchpadScreen` `remember`s it per song); a fresh
 * instance just starts from what [load] read back.
 *
 * **Atomic.** New audio files land under fresh names (never overwriting a
 * file the current manifest uses), the manifest is replaced by an atomic
 * rename as the single commit point, and only after that are files the new
 * manifest no longer references deleted. A crash or kill at any step
 * leaves either the old or the new manifest on disk, each with every file
 * it references still present.
 */
class MultitrackProjectStorage(private val projectDir: File) {
    constructor(context: Context, songId: String) : this(File(File(context.filesDir, "scratchpad"), songId))

    private val clipsDir = File(projectDir, "clips")
    private val manifestFile = File(projectDir, "manifest.json")

    /** Audio already on disk, by buffer identity — see the class doc's "Incremental". */
    private val writtenBuffers = IdentityHashMap<FloatArray, String>()

    /** True if a saved project exists on disk. Cheap — doesn't read or parse it. */
    fun exists(): Boolean = manifestFile.exists()

    /**
     * Writes [project] to disk, replacing whatever was saved before. Only
     * audio buffers not already on disk are written. Not RT-safe (file
     * I/O) — call from a background dispatcher. Serialized against other
     * calls on this instance, so overlapping auto-saves can't interleave.
     */
    @Synchronized
    fun save(project: MultitrackProject) {
        clipsDir.mkdirs()

        val tracksJson = JSONArray()
        for (track in project.tracks) {
            val clipsJson = JSONArray()
            for (clip in track.clips) {
                clipsJson.put(
                    JSONObject()
                        .put("file", fileFor(clip.buffer))
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
        val manifest = JSONObject()
            .put("tracks", tracksJson)
            .put("bpm", project.bpm)
            .put("beatsPerBar", project.beatsPerBar)
            .put("metronomeOn", project.metronomeOn)
            .put("countInBars", project.countInBars)
            .toString()

        val tmp = File(projectDir, "manifest.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(manifest.toByteArray())
            out.fd.sync()
        }
        Files.move(tmp.toPath(), manifestFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        // Only now, with the new manifest committed, is anything it no
        // longer references safe to delete (including stray .tmp files
        // from a save that died before its rename).
        val referenced = project.tracks.flatMap { t -> t.clips.map { writtenBuffers[it.buffer] } }.toSet()
        clipsDir.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
        writtenBuffers.values.retainAll(referenced)
    }

    /** The file holding [buffer], writing it first if it isn't on disk yet. */
    private fun fileFor(buffer: FloatArray): String {
        writtenBuffers[buffer]?.let { name -> if (File(clipsDir, name).exists()) return name }
        val name = "take_${UUID.randomUUID()}.f32"
        val tmp = File(clipsDir, "$name.tmp")
        writeF32(tmp, buffer)
        Files.move(tmp.toPath(), File(clipsDir, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        writtenBuffers[buffer] = name
        return name
    }

    /** Reads the saved project back, or null if [exists] would return false. */
    @Synchronized
    fun load(): MultitrackProject? {
        if (!manifestFile.exists()) return null
        val root = JSONObject(manifestFile.readText())
        // Clips naming the same file share one FloatArray, in memory and
        // in writtenBuffers. (A manifest from before buffers were shared
        // has one clip_N.f32 per clip and simply loads as separate
        // buffers; the next save rewrites it under take_ names.)
        val buffersByFile = HashMap<String, FloatArray>()
        val tracksJson = root.getJSONArray("tracks")
        val tracks = (0 until tracksJson.length()).map { i ->
            val trackJson = tracksJson.getJSONObject(i)
            val clipsJson = trackJson.getJSONArray("clips")
            val clips = (0 until clipsJson.length()).map { c ->
                val clipJson = clipsJson.getJSONObject(c)
                val fileName = clipJson.getString("file")
                val buffer = buffersByFile.getOrPut(fileName) { readF32(File(clipsDir, fileName)) }
                MultitrackClipSpec(
                    buffer = buffer,
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
        writtenBuffers.clear()
        buffersByFile.forEach { (name, buffer) -> writtenBuffers[buffer] = name }
        // opt* fall back to MultitrackProject's own defaults for a manifest
        // saved before a field existed — old scratchpads still load
        // instead of failing to parse.
        val defaults = MultitrackProject()
        return MultitrackProject(
            tracks = tracks,
            bpm = root.optDouble("bpm", defaults.bpm),
            beatsPerBar = root.optInt("beatsPerBar", defaults.beatsPerBar),
            metronomeOn = root.optBoolean("metronomeOn", defaults.metronomeOn),
            countInBars = root.optInt("countInBars", defaults.countInBars),
        )
    }

    /** Deletes the saved project (manifest + every clip file) entirely. */
    @Synchronized
    fun clear() {
        projectDir.deleteRecursively()
        writtenBuffers.clear()
    }

    companion object {
        private const val kChunkBytes = 64 * 1024

        /**
         * Reads a raw native-order float32 file straight into a FloatArray
         * through a small reusable chunk buffer — the file's bytes are
         * never held in memory alongside the samples, which matters for a
         * multi-minute take (~11.5 MB per minute at 48 kHz mono).
         */
        fun readF32(file: File): FloatArray {
            val samples = FloatArray((file.length() / 4).toInt())
            val chunk = ByteBuffer.allocateDirect(kChunkBytes).order(ByteOrder.nativeOrder())
            FileInputStream(file).channel.use { channel ->
                var pos = 0
                while (pos < samples.size) {
                    chunk.clear()
                    while (chunk.hasRemaining() && channel.read(chunk) >= 0) Unit
                    chunk.flip()
                    val count = minOf(chunk.remaining() / 4, samples.size - pos)
                    if (count == 0) break
                    chunk.asFloatBuffer().get(samples, pos, count)
                    pos += count
                }
            }
            return samples
        }

        private fun writeF32(file: File, samples: FloatArray) {
            val chunk = ByteBuffer.allocateDirect(kChunkBytes).order(ByteOrder.nativeOrder())
            val floats = chunk.asFloatBuffer()
            FileOutputStream(file).use { out ->
                val channel = out.channel
                var pos = 0
                while (pos < samples.size) {
                    val count = minOf(floats.capacity(), samples.size - pos)
                    floats.clear()
                    floats.put(samples, pos, count)
                    chunk.clear()
                    chunk.limit(count * 4)
                    while (chunk.hasRemaining()) channel.write(chunk)
                    pos += count
                }
                channel.force(true)
            }
        }
    }
}
