package com.songnotes.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the hand-transcribed [CHORD_DB]/[lookupChord] port against
 * `spec/chord-db.json` (a literal dump of the real web app's `CHORD_DB`
 * object) and `spec/lookup-chord.json` (fixtures from the real
 * `lookupChord`) — see [CHORD_DB]'s own doc comment for why this data gets
 * a verifying test rather than a fixture-driven parameterized run the way
 * [normalizeChordName] does.
 */
class ChordDbGoldenFixtureTest {

    @Suppress("UNCHECKED_CAST")
    private fun loadFixture(resourceName: String): Any? {
        val text = requireNotNull(javaClass.getResourceAsStream(resourceName)) {
            "Missing test resource $resourceName"
        }.bufferedReader(Charsets.UTF_8).readText()
        return MinimalJson.parse(text)
    }

    @Suppress("UNCHECKED_CAST")
    private fun voicingFromJson(json: Map<String, Any?>): ChordVoicing {
        val frets = (json["frets"] as List<Double>).map { it.toInt() }
        val baseFret = (json["baseFret"] as Double).toInt()
        val barreJson = json["barre"] as Map<String, Any?>?
        val barre = barreJson?.let {
            ChordBarre(
                fret = (it["fret"] as Double).toInt(),
                fromString = (it["fromString"] as Double).toInt(),
                toString = (it["toString"] as Double).toInt(),
            )
        }
        return ChordVoicing(frets, baseFret, barre)
    }

    @Test
    fun `CHORD_DB matches the real web app's CHORD_DB exactly, key for key and value for value`() {
        @Suppress("UNCHECKED_CAST")
        val fixture = loadFixture("/spec/chord-db.json") as Map<String, Any?>
        assertTrue("expected a non-trivial fixture set", fixture.size > 50)

        assertEquals("CHORD_DB has a different key set than the real JS CHORD_DB", fixture.keys, CHORD_DB.keys)

        val mismatches = StringBuilder()
        var failures = 0
        for ((name, json) in fixture) {
            @Suppress("UNCHECKED_CAST")
            val expected = voicingFromJson(json as Map<String, Any?>)
            val actual = CHORD_DB[name]
            if (actual != expected) {
                failures++
                mismatches.appendLine("  $name: expected=$expected actual=$actual")
            }
        }
        assertEquals("CHORD_DB disagreed with the JS fixture on $failures entries:\n$mismatches", 0, failures)
    }

    @Test
    fun `lookupChord matches the real lookupChord fixtures exactly`() {
        @Suppress("UNCHECKED_CAST")
        val fixtures = loadFixture("/spec/lookup-chord.json") as List<Map<String, Any?>>
        assertTrue("expected a non-trivial fixture set", fixtures.isNotEmpty())

        var failures = 0
        val failureExamples = StringBuilder()
        for (fixture in fixtures) {
            val input = fixture["input"] as String?
            @Suppress("UNCHECKED_CAST")
            val customChordsJson = fixture["customChords"] as Map<String, Any?>?
            val customChords = customChordsJson?.mapValues { (_, v) ->
                @Suppress("UNCHECKED_CAST")
                voicingFromJson(v as Map<String, Any?>)
            }
            @Suppress("UNCHECKED_CAST")
            val expectedJson = fixture["output"] as Map<String, Any?>?
            val expected = expectedJson?.let { voicingFromJson(it) }

            val actual = lookupChord(input, customChords)
            if (actual != expected) {
                failures++
                if (failures <= 20) {
                    failureExamples.appendLine("  input=$input customChords=$customChords expected=$expected actual=$actual")
                }
            }
        }
        assertEquals(
            "lookupChord disagreed with the JS fixtures on $failures/${fixtures.size} cases:\n$failureExamples",
            0,
            failures,
        )
    }
}
