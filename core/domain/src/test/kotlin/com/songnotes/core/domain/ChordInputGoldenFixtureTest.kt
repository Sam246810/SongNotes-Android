package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Same cross-check strategy as [ChordsGoldenFixtureTest], applied to
 * [formatFretsForInput], [parseFretsInput], and [alignChordsWithLyrics] —
 * the custom-voicing-entry and chord/lyric-alignment helpers that round
 * out `chords.js`'s fully portable surface (everything except
 * `lookupChord` and the real `CHORD_DB` voicing/fret data, both deferred
 * to Phase 8 — see `Chords.kt`'s own doc comment).
 */
class ChordInputGoldenFixtureTest {

    @Suppress("UNCHECKED_CAST")
    private fun loadFixtures(resourceName: String): List<Map<String, Any?>> {
        val text = requireNotNull(javaClass.getResourceAsStream(resourceName)) {
            "Missing test resource $resourceName"
        }.bufferedReader(Charsets.UTF_8).readText()
        return MinimalJson.parse(text) as List<Map<String, Any?>>
    }

    @Test
    fun `formatFretsForInput matches the JS fixtures exactly`() {
        val fixtures = loadFixtures("/spec/format-frets-for-input.json")
        assertEquals("expected a non-trivial fixture set", true, fixtures.isNotEmpty())

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            @Suppress("UNCHECKED_CAST")
            val input = (fixture["input"] as List<Double>).map { it.toInt() }
            val expected = fixture["output"] as String
            val actual = formatFretsForInput(input)
            if (actual != expected) {
                failures++
                if (failures <= 20) failureExamples.appendLine("  input=$input expected=$expected actual=$actual")
            }
        }
        assertEquals(
            "formatFretsForInput disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples",
            0,
            failures,
        )
    }

    @Test
    fun `parseFretsInput matches the JS fixtures exactly`() {
        val fixtures = loadFixtures("/spec/parse-frets-input.json")
        assertEquals("expected a non-trivial fixture set", true, fixtures.isNotEmpty())

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            val input = fixture["input"] as String?
            val expectedRaw = fixture["output"]
            val actual = parseFretsInput(input)

            val mismatch = if (expectedRaw == null) {
                actual != null
            } else {
                @Suppress("UNCHECKED_CAST")
                val expected = expectedRaw as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val expectedFrets = (expected["frets"] as List<Double>).map { it.toInt() }
                val expectedBaseFret = (expected["baseFret"] as Double).toInt()
                actual == null || actual.frets != expectedFrets || actual.baseFret != expectedBaseFret
            }
            if (mismatch) {
                failures++
                if (failures <= 20) {
                    failureExamples.appendLine("  input=$input expected=$expectedRaw actual=$actual")
                }
            }
        }
        assertEquals(
            "parseFretsInput disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples",
            0,
            failures,
        )
    }

    @Test
    fun `alignChordsWithLyrics matches the JS fixtures exactly`() {
        val fixtures = loadFixtures("/spec/align-chords-with-lyrics.json")
        assertEquals("expected a non-trivial fixture set", true, fixtures.isNotEmpty())

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            val chords = fixture["chords"] as String?
            val lyrics = fixture["lyrics"] as String?
            val expected = fixture["output"] as String
            val actual = alignChordsWithLyrics(chords, lyrics)
            if (actual != expected) {
                failures++
                if (failures <= 20) {
                    failureExamples.appendLine("  chords=$chords lyrics=$lyrics expected=$expected actual=$actual")
                }
            }
        }
        assertEquals(
            "alignChordsWithLyrics disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples",
            0,
            failures,
        )
    }
}
