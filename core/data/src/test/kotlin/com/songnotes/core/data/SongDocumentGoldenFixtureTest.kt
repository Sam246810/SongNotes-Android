package com.songnotes.core.data

import com.songnotes.core.domain.ChordAnchor
import com.songnotes.core.domain.ChordBarre
import com.songnotes.core.domain.ChordVoicing
import com.songnotes.core.domain.Song
import com.songnotes.core.domain.SongLine
import com.songnotes.core.domain.SongMeta
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The song document both apps encrypt into a row's `content`, checked against
 * `spec/song-documents.json` -- generated ONLY by the web repo's
 * `src/test/generate-golden-fixtures.test.js` from its real `songFromDocument`,
 * and read-only here, same arrangement as [RecoveryCodeGoldenFixtureTest]: copy
 * a fresh file into this module's `src/test/resources/spec/` by hand whenever
 * it changes, byte-identical.
 *
 * Each case is a document as some writer produced it -- the web app today,
 * this app, and the legacy shapes still found in old web rows -- with the song
 * the web reader makes of it. [songFromDocument] must make the same song, or
 * a song would read differently (or not at all) on one side of a sync.
 */
class SongDocumentGoldenFixtureTest {

    @Test
    fun `every fixture document reads as the web app reads it`() {
        val cases = loadCases()
        assertTrue("expected the full case set, got ${cases.length()}", cases.length() >= 5)

        for (k in 0 until cases.length()) {
            val case = cases.getJSONObject(k)
            val name = case.getString("name")
            val actual = songFromDocument(case.getString("id"), case.getJSONObject("document"))
            assertEquals(name, expectedSong(case.getJSONObject("expected")), actual)
        }
    }

    @Test
    fun `this app writes exactly the Android case's document`() {
        val case = caseNamed("Android app")
        val song = songFromDocument(case.getString("id"), case.getJSONObject("document"))
        // songToDocument is what SyncEngine.buildRow encrypts.
        assertEquals(normalize(case.getJSONObject("document")), normalize(songToDocument(song)))
    }

    @Test
    fun `a document without dates takes them from the row, not the clock`() {
        val doc = JSONObject().put("title", "No dates").put("lines", JSONArray())
        val song = songFromDocument("row-id", doc, rowCreatedAt = "2026-01-05T12:00:00+00:00", rowUpdatedAt = "2026-01-06T08:30:00.250+00:00")
        assertEquals(OffsetDateTime.parse("2026-01-05T12:00:00Z").toInstant().toEpochMilli(), song.createdAt)
        assertEquals(OffsetDateTime.parse("2026-01-06T08:30:00.250Z").toInstant().toEpochMilli(), song.updatedAt)
    }

    @Test
    fun `bpm and capo read like the web's toMetaNumber`() {
        assertEquals(92, toMetaNumber(92))
        assertEquals(92, toMetaNumber(92.7))
        assertEquals(92, toMetaNumber("92"))
        assertEquals(92, toMetaNumber(" 92 bpm"))
        assertEquals(0, toMetaNumber(""))
        assertEquals(0, toMetaNumber("abc"))
        assertEquals(0, toMetaNumber(-3))
        assertEquals(0, toMetaNumber("0.5"))
        assertEquals(0, toMetaNumber(null))
        assertEquals(0, toMetaNumber(JSONObject.NULL))
        assertEquals(0, toMetaNumber(true))
    }

    /** The expected song, built straight from the fixture's `expected` (the web's song shape) without going through the reader under test. */
    private fun expectedSong(json: JSONObject): Song {
        val lines = json.getJSONArray("lines")
        val custom = json.getJSONObject("customChords")
        return Song(
            id = json.getString("id"),
            title = json.getString("title"),
            meta = SongMeta(bpm = json.getInt("bpm"), key = json.getString("key"), tuning = json.getString("tuning"), capo = json.getInt("capo")),
            lines = (0 until lines.length()).map { i ->
                val line = lines.getJSONObject(i)
                val chords = line.getJSONArray("chords")
                SongLine(
                    id = line.getString("id"),
                    lyrics = line.getString("lyrics"),
                    chords = (0 until chords.length()).map { c -> chords.getJSONObject(c).let { ChordAnchor(it.getInt("i"), it.getString("c")) } },
                )
            },
            customChords = custom.keys().asSequence().associateWith { name ->
                val v = custom.getJSONObject(name)
                val frets = v.getJSONArray("frets")
                ChordVoicing(
                    frets = (0 until frets.length()).map { frets.getInt(it) },
                    baseFret = v.getInt("baseFret"),
                    barre = v.optJSONObject("barre")?.let { ChordBarre(it.getInt("fret"), it.getInt("fromString"), it.getInt("toString")) },
                )
            },
            createdAt = OffsetDateTime.parse(json.getString("createdAt")).toInstant().toEpochMilli(),
            updatedAt = OffsetDateTime.parse(json.getString("updatedAt")).toInstant().toEpochMilli(),
        )
    }

    /** JSON as plain values -- objects as maps, so key order doesn't matter; whole numbers as Long, so 4 and 4L compare equal. */
    private fun normalize(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { normalize(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalize(value.get(it)) }
        is Number -> if (value.toDouble() % 1.0 == 0.0) value.toLong() else value.toDouble()
        else -> value
    }

    private fun caseNamed(prefix: String): JSONObject {
        val cases = loadCases()
        return (0 until cases.length()).map { cases.getJSONObject(it) }.first { it.getString("name").startsWith(prefix) }
    }

    private fun loadCases(): JSONArray {
        val text = requireNotNull(javaClass.getResourceAsStream("/spec/song-documents.json")) {
            "Missing test resource /spec/song-documents.json -- copy it from the web repo's spec/"
        }.bufferedReader(Charsets.UTF_8).readText()
        return JSONTokener(text).nextValue() as JSONArray
    }
}
