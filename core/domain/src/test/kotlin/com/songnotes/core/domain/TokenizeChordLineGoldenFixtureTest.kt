package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Same cross-check strategy as [ChordsGoldenFixtureTest], applied to [tokenizeChordLine]. */
class TokenizeChordLineGoldenFixtureTest {

    @Suppress("UNCHECKED_CAST")
    private fun loadFixtures(resourceName: String): List<Map<String, Any?>> {
        val text = requireNotNull(javaClass.getResourceAsStream(resourceName)) {
            "Missing test resource $resourceName"
        }.bufferedReader(Charsets.UTF_8).readText()
        return MinimalJson.parse(text) as List<Map<String, Any?>>
    }

    private fun String.repr(): String = "\"" + this.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun `tokenizeChordLine matches the JS fixtures exactly`() {
        @Suppress("UNCHECKED_CAST")
        val fixtures = loadFixtures("/spec/tokenize-chord-line.json")
        assertEquals("expected a non-trivial fixture set", true, fixtures.isNotEmpty())

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            val input = fixture["input"] as String
            @Suppress("UNCHECKED_CAST")
            val expectedTokens = fixture["output"] as List<Map<String, Any?>>
            val actualTokens = tokenizeChordLine(input)

            val mismatch = expectedTokens.size != actualTokens.size || expectedTokens.indices.any { i ->
                val e = expectedTokens[i]
                val a = actualTokens[i]
                e["text"] != a.text ||
                    e["isChord"] != a.isChord ||
                    e["looksLikeChord"] != a.looksLikeChord ||
                    e["isWhitespace"] != a.isWhitespace ||
                    (e["chordName"] as String?) != a.chordName
            }
            if (mismatch) {
                failures++
                if (failures <= 10) {
                    failureExamples.appendLine(
                        "  input=${input.repr()}\n    expected=$expectedTokens\n    actual=$actualTokens",
                    )
                }
            }
        }
        assertEquals(
            "tokenizeChordLine disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples",
            0,
            failures,
        )
    }
}
