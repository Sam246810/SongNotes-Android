package com.songnotes.core.audio

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MultitrackProjectStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun take(size: Int, seed: Float) = FloatArray(size) { seed + it * 0.001f }

    private fun clipFiles(dir: File) = File(dir, "clips").listFiles()!!.map { it.name }.sorted()

    @Test
    fun `round trip preserves tracks, clips, and audio`() {
        val dir = tmp.newFolder("p")
        val a = take(100_000, 0.1f) // spans more than one 64 KB I/O chunk
        val project = MultitrackProject(
            tracks = listOf(
                MultitrackTrackSpec(clips = listOf(MultitrackClipSpec(a, startFrame = 10)), gain = 0.5f, muted = true),
                MultitrackTrackSpec(clips = emptyList(), soloed = true),
            ),
            bpm = 87.0,
            beatsPerBar = 3,
            metronomeOn = false,
            countInBars = 2,
        )
        MultitrackProjectStorage(dir).save(project)

        val loaded = MultitrackProjectStorage(dir).load()!!
        assertEquals(87.0, loaded.bpm, 0.0)
        assertEquals(3, loaded.beatsPerBar)
        assertEquals(false, loaded.metronomeOn)
        assertEquals(2, loaded.countInBars)
        assertEquals(0.5f, loaded.tracks[0].gain)
        assertEquals(true, loaded.tracks[0].muted)
        assertEquals(true, loaded.tracks[1].soloed)
        assertEquals(10L, loaded.tracks[0].clips[0].startFrame)
        assertArrayEquals(a, loaded.tracks[0].clips[0].buffer, 0f)
    }

    @Test
    fun `a buffer shared by two clips is written once and loads shared`() {
        val dir = tmp.newFolder("p")
        val a = take(1000, 0.2f)
        val head = MultitrackClipSpec(a, startFrame = 0, bufferOffsetFrames = 0, lengthFrames = 300)
        val tail = MultitrackClipSpec(a, startFrame = 600, bufferOffsetFrames = 600, lengthFrames = 400)
        MultitrackProjectStorage(dir).save(MultitrackProject(tracks = listOf(MultitrackTrackSpec(listOf(head, tail)))))

        assertEquals(1, clipFiles(dir).size)
        val clips = MultitrackProjectStorage(dir).load()!!.tracks[0].clips
        assertSame(clips[0].buffer, clips[1].buffer)
        assertEquals(600L, clips[1].bufferOffsetFrames)
    }

    @Test
    fun `saving again without audio changes rewrites no audio`() {
        val dir = tmp.newFolder("p")
        val storage = MultitrackProjectStorage(dir)
        val project = MultitrackProject(tracks = listOf(MultitrackTrackSpec(listOf(MultitrackClipSpec(take(500, 0f), 0)))))
        storage.save(project)
        val before = clipFiles(dir)
        val file = File(File(dir, "clips"), before.single())
        file.setLastModified(1_000L)

        storage.save(project.withTrackGain(0, 0.25f))

        assertEquals(before, clipFiles(dir))
        assertEquals(1_000L, file.lastModified())
        assertEquals(0.25f, MultitrackProjectStorage(dir).load()!!.tracks[0].gain)
    }

    @Test
    fun `removed audio is deleted, and reloaded buffers are not rewritten`() {
        val dir = tmp.newFolder("p")
        val a = take(200, 0.3f)
        val b = take(300, 0.4f)
        MultitrackProjectStorage(dir).save(
            MultitrackProject(tracks = listOf(MultitrackTrackSpec(listOf(MultitrackClipSpec(a, 0), MultitrackClipSpec(b, 500))))),
        )
        assertEquals(2, clipFiles(dir).size)

        val storage = MultitrackProjectStorage(dir)
        val loaded = storage.load()!!
        val keptFile = clipFiles(dir).toSet()
        storage.save(loaded.copy(tracks = listOf(MultitrackTrackSpec(listOf(loaded.tracks[0].clips[0])))))

        val after = clipFiles(dir)
        assertEquals(1, after.size)
        assert(after.single() in keptFile) // the surviving buffer kept its original file
    }

    @Test
    fun `a save that died before its manifest rename leaves the previous project loadable`() {
        val dir = tmp.newFolder("p")
        val a = take(400, 0.5f)
        MultitrackProjectStorage(dir).save(MultitrackProject(tracks = listOf(MultitrackTrackSpec(listOf(MultitrackClipSpec(a, 7))))))
        // Simulate a crash mid-save: a new take written, the new manifest
        // half-written to its temp name, the rename never reached.
        File(File(dir, "clips"), "take_orphan.f32.tmp").writeBytes(ByteArray(16))
        File(dir, "manifest.json.tmp").writeText("{\"tracks\": [")

        val loaded = MultitrackProjectStorage(dir).load()!!
        assertEquals(7L, loaded.tracks[0].clips[0].startFrame)
        assertArrayEquals(a, loaded.tracks[0].clips[0].buffer, 0f)

        // ...and the next real save sweeps the stray temp file.
        MultitrackProjectStorage(dir).save(loaded)
        assertEquals(1, clipFiles(dir).size)
    }

    @Test
    fun `old per-clip manifests still load`() {
        val dir = tmp.newFolder("p")
        val clips = File(dir, "clips").apply { mkdirs() }
        val a = take(50, 0.6f)
        // Write via a throwaway project, then rename to the legacy name.
        MultitrackProjectStorage(tmp.newFolder("scratch")).save(MultitrackProject(tracks = listOf(MultitrackTrackSpec(listOf(MultitrackClipSpec(a, 0))))))
        File(tmp.root, "scratch/clips").listFiles()!!.single().copyTo(File(clips, "clip_0.f32"))
        File(dir, "manifest.json").writeText(
            """{"tracks":[{"gain":1.0,"muted":false,"soloed":false,"clips":[
               {"file":"clip_0.f32","startFrame":0,"bufferOffsetFrames":0,"lengthFrames":50}]}]}""",
        )

        val loaded = MultitrackProjectStorage(dir).load()!!
        assertArrayEquals(a, loaded.tracks[0].clips[0].buffer, 0f)
        assertEquals(100.0, loaded.bpm, 0.0) // defaults for fields an old manifest predates
        assertEquals(true, loaded.metronomeOn)
        assertEquals(1, loaded.countInBars)
    }
}
