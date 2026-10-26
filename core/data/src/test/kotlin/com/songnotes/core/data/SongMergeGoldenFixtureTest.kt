package com.songnotes.core.data

import com.songnotes.core.domain.merge.contentEquals
import com.songnotes.core.domain.merge.mergeSongs
import com.songnotes.core.domain.merge.stampEdits
import com.songnotes.core.domain.merge.tokenize
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sync merge (docs/SYNC-MERGE.md), checked against `spec/song-merge.json`
 * -- generated ONLY by the web repo's `src/test/generate-golden-fixtures.test.js`
 * from its real `mergeSongs` (each case also has a hand-checked expectation in
 * the web's `songMerge.test.js`), and read-only here: copy a fresh file into
 * this module's `src/test/resources/spec/` by hand whenever it changes,
 * byte-identical.
 *
 * Every case is read with this app's own [songFromDocument], merged with the
 * Kotlin [mergeSongs], and compared with the web's result as a song (dates as
 * epoch ms -- the two writers format ISO dates differently). If the two
 * engines ever disagree, the same race would merge differently depending on
 * which device lost it.
 */
class SongMergeGoldenFixtureTest {

    private val fixture: JSONObject by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/spec/song-merge.json")) {
            "Missing test resource /spec/song-merge.json -- copy it from the web repo's spec/"
        }.bufferedReader(Charsets.UTF_8).readText()
        JSONTokener(text).nextValue() as JSONObject
    }

    private fun read(id: String, doc: Any?): Song? = (doc as? JSONObject)?.let { songFromDocument(id, it) }

    private fun cases(): List<JSONObject> = fixture.getJSONArray("cases").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }

    @Test
    fun `tokenizes exactly as the web app does`() {
        val vectors = fixture.getJSONArray("tokenize")
        for (k in 0 until vectors.length()) {
            val v = vectors.getJSONObject(k)
            val expected = v.getJSONArray("tokens").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            assertEquals(v.getString("text"), expected, tokenize(v.getString("text")))
        }
    }

    @Test
    fun `every case merges to the web app's result`() {
        val cases = cases()
        assertTrue("expected the full case set, got ${cases.size}", cases.size > 30)
        for (case in cases) {
            val id = case.getString("id")
            val merged = mergeSongs(read(id, case.opt("base")), read(id, case.get("local"))!!, read(id, case.get("remote"))!!)
            assertEquals(case.getString("name"), read(id, case.get("expected")), merged)
        }
    }

    @Test
    fun `merging is symmetric whenever the base is known`() {
        for (case in cases().filter { it.getBoolean("symmetric") }) {
            val id = case.getString("id")
            val base = read(id, case.opt("base"))
            val local = read(id, case.get("local"))!!
            val remote = read(id, case.get("remote"))!!
            assertEquals(case.getString("name"), mergeSongs(base, local, remote), mergeSongs(base, remote, local))
        }
    }

    @Test
    fun `this app writes merged songs back as the web wrote them`() {
        for (case in cases()) {
            val id = case.getString("id")
            val merged = mergeSongs(read(id, case.opt("base")), read(id, case.get("local"))!!, read(id, case.get("remote"))!!)
            assertTrue(case.getString("name"), contentEquals(read(id, songToDocument(merged)), read(id, case.get("expected"))))
        }
    }

    @Test
    fun `stampEdits stamps what changed and keeps the rest`() {
        val prev = Song(
            id = "s", title = "T",
            lines = listOf(SongLine("a", "one", editedAt = 100), SongLine("b", "two")),
            createdAt = 0, updatedAt = 0,
        )
        val next = stampEdits(prev, prev.copy(title = "New", lines = listOf(SongLine("a", "one"), SongLine("b", "TWO"), SongLine("c", "new"))), 5000)
        assertEquals(listOf(100L, 5000L, 5000L), next.lines.map { it.editedAt })
        assertEquals(mapOf("title" to 5000L), next.fieldEditedAt)
        // Never backwards, even when the clock is.
        val again = stampEdits(next, next.copy(lines = next.lines.map { if (it.id == "a") it.copy(lyrics = "uno") else it }), 50)
        assertEquals(101L, again.lines[0].editedAt)
    }

    @Suppress("unused")
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
