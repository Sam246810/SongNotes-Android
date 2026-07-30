package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Same cross-check strategy as [ChordsGoldenFixtureTest], applied to
 * [looksLikeChordLine] and [parseLyricsText]. The `parse-lyrics-text.json`
 * fixtures are the 25 hand-crafted scenarios from the desktop repo's own
 * `src/test/lyricsImport.test.js` (title header, meta header block,
 * bracketed chord cues, section markers, mixed notation conventions,
 * Windows line endings, PDF-style indentation) plus one longer realistic
 * multi-section song — every one of these targets a specific parsing
 * decision, not a combinatorially generated input.
 */
class LyricsImportGoldenFixtureTest {

    @Suppress("UNCHECKED_CAST")
    private fun loadFixtures(resourceName: String): List<Map<String, Any?>> {
        val text = requireNotNull(javaClass.getResourceAsStream(resourceName)) {
            "Missing test resource $resourceName"
        }.bufferedReader(Charsets.UTF_8).readText()
        return MinimalJson.parse(text) as List<Map<String, Any?>>
    }

    private fun String.repr(): String = "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `looksLikeChordLine matches the JS fixtures exactly`() {
        val fixtures = loadFixtures("/spec/looks-like-chord-line.json")
        assertEquals("expected a non-trivial fixture set", true, fixtures.isNotEmpty())

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            val input = fixture["input"] as String
            val expected = fixture["output"] as Boolean
            val actual = looksLikeChordLine(input)
            if (actual != expected) {
                failures++
                if (failures <= 20) {
                    failureExamples.appendLine("  input=${input.repr()} expected=$expected actual=$actual")
                }
            }
        }
        assertEquals(
            "looksLikeChordLine disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples",
            0,
            failures,
        )
    }

    @Test
    fun `parseLyricsText matches the JS fixtures exactly`() {
        val fixtures = loadFixtures("/spec/parse-lyrics-text.json")
        assertEquals("expected a non-trivial fixture set", true, fixtures.isNotEmpty())

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            val input = fixture["input"] as String
            @Suppress("UNCHECKED_CAST")
            val expected = fixture["output"] as Map<String, Any?>
            val actual = parseLyricsText(input)

            val expectedTitle = expected["title"] as String?
            @Suppress("UNCHECKED_CAST")
            val expectedMeta = (expected["meta"] as Map<String, Any?>).mapValues { it.value as String }
            @Suppress("UNCHECKED_CAST")
            val expectedLines = (expected["lines"] as List<Map<String, Any?>>).map {
                LyricsLine(chords = it["chords"] as String, lyrics = it["lyrics"] as String)
            }

            val mismatch = expectedTitle != actual.title || expectedMeta != actual.meta || expectedLines != actual.lines
            if (mismatch) {
                failures++
                if (failures <= 10) {
                    failureExamples.appendLine(
                        "  input=${input.repr()}\n" +
                            "    expected title=$expectedTitle meta=$expectedMeta lines=$expectedLines\n" +
                            "    actual   title=${actual.title} meta=${actual.meta} lines=${actual.lines}",
                    )
                }
            }
        }
        assertEquals(
            "parseLyricsText disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples",
            0,
            failures,
        )
    }
}
