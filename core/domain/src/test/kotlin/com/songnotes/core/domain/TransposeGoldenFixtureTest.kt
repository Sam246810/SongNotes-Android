package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Same cross-check strategy as [ChordsGoldenFixtureTest], applied to
 * [transposeChordToken] and [transposeChordsLine] against fixtures
 * generated from the real JS implementations.
 */
class TransposeGoldenFixtureTest {

    @Suppress("UNCHECKED_CAST")
    private fun loadFixtures(resourceName: String): List<Map<String, Any?>> {
        val text = requireNotNull(javaClass.getResourceAsStream(resourceName)) {
            "Missing test resource $resourceName"
        }.bufferedReader(Charsets.UTF_8).readText()
        return MinimalJson.parse(text) as List<Map<String, Any?>>
    }

    private fun String.repr(): String = "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `transposeChordToken matches the JS fixtures exactly`() {
        val fixtures = loadFixtures("/spec/transpose-chord-token.json")
        assertEquals("expected a substantial fixture set", true, fixtures.size > 1000)

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            val input = fixture["input"] as String
            val semitones = (fixture["semitones"] as Double).toInt()
            val expected = fixture["output"] as String
            val actual = transposeChordToken(input, semitones)
            if (actual != expected) {
                failures++
                if (failures <= 20) {
                    failureExamples.appendLine(
                        "  input=${input.repr()} semitones=$semitones expected=${expected.repr()} actual=${actual?.repr()}",
                    )
                }
            }
        }
        assertEquals(
            "transposeChordToken disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples",
            0,
            failures,
        )
    }

    @Test
    fun `transposeChordsLine matches the JS fixtures exactly`() {
        val fixtures = loadFixtures("/spec/transpose-chords-line.json")
        assertEquals("expected a non-trivial fixture set", true, fixtures.isNotEmpty())

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            val input = fixture["input"] as String
            val semitones = (fixture["semitones"] as Double).toInt()
            val expected = fixture["output"] as String
            val actual = transposeChordsLine(input, semitones)
            if (actual != expected) {
                failures++
                if (failures <= 20) {
                    failureExamples.appendLine(
                        "  input=${input.repr()} semitones=$semitones expected=${expected.repr()} actual=${actual?.repr()}",
                    )
                }
            }
        }
        assertEquals(
            "transposeChordsLine disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples",
            0,
            failures,
        )
    }
}
