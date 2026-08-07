package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [formatSongAsText] against `spec/export-to-text.json`, fixtures
 * generated from the real web app's `exportToText` -- see [formatSongAsText]'s
 * own doc comment for why chords round-trip through [chordsLineToAnchors]
 * when reconstructing each fixture's [Song].
 */
class ExportGoldenFixtureTest {

    @Suppress("UNCHECKED_CAST")
    private fun loadFixtures(): List<Map<String, Any?>> {
        val text = requireNotNull(javaClass.getResourceAsStream("/spec/export-to-text.json")) {
            "Missing test resource /spec/export-to-text.json"
        }.bufferedReader(Charsets.UTF_8).readText()
        return MinimalJson.parse(text) as List<Map<String, Any?>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun songFromFixtureInput(input: Map<String, Any?>): Song {
        val title = input["title"] as String
        val linesJson = input["lines"] as List<Map<String, Any?>>
        val lines = linesJson.mapIndexed { i, lineJson ->
            val chordsLine = lineJson["chords"] as String
            val lyrics = lineJson["lyrics"] as String
            SongLine(id = "line-$i", lyrics = lyrics, chords = chordsLineToAnchors(chordsLine))
        }
        return Song(id = "song", title = title, lines = lines, createdAt = 0L, updatedAt = 0L)
    }

    @Test
    fun `formatSongAsText matches the JS fixtures exactly`() {
        val fixtures = loadFixtures()
        assertTrue("expected a non-trivial fixture set", fixtures.isNotEmpty())

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            @Suppress("UNCHECKED_CAST")
            val input = fixture["input"] as Map<String, Any?>
            val expected = fixture["output"] as String
            val song = songFromFixtureInput(input)
            val actual = formatSongAsText(song)
            if (actual != expected) {
                failures++
                failureExamples.appendLine("  input=$input\n    expected=${expected.replace("\n", "\\n")}\n    actual=${actual.replace("\n", "\\n")}")
            }
        }
        assertEquals("formatSongAsText disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples", 0, failures)
    }
}
