package com.songnotes.core.data

import java.util.Base64
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation test vectors for recovery-code normalization + KEK
 * derivation -- the Kotlin twin of [EnvelopeV2GoldenFixtureTest], but
 * deliberately READ-ONLY: `spec/recovery-code-vectors.json` is generated
 * ONLY by the web repo's `src/test/generate-golden-fixtures.test.js` (both
 * `normalizeRecoveryCode` and the fixed-salt Argon2id derivation it exercises
 * are pure functions of their inputs, so there's no "real random DEK" reason
 * for a reverse Kotlin-writes-a-fixture direction to exist at all here).
 *
 * This test never writes anything, anywhere -- not even to this module's own
 * `src/test/resources/spec/`. [EnvelopeV2GoldenFixtureTest]'s
 * `writeIfParentExists` helper (which DOES write, including into the sibling
 * web repo's `spec/` directory) is a hazard specifically because a fixture
 * generation step lived on the Kotlin side at all; this file has no such step
 * to begin with, so the hazard can't recur here by construction. Copy a fresh
 * `spec/recovery-code-vectors.json` from the web repo into this module's
 * `src/test/resources/spec/` by hand whenever it changes.
 */
class RecoveryCodeGoldenFixtureTest {

    @Test
    fun `normalizeRecoveryCode matches the web app's real implementation across every fixture`() {
        val fixtures = loadFixtures().getJSONArray("normalize")
        assertTrue("expected a broad fixture set, got ${fixtures.length()}", fixtures.length() > 30)

        for (i in 0 until fixtures.length()) {
            val entry = fixtures.getJSONObject(i)
            val input = if (entry.isNull("input")) null else entry.getString("input")
            val expected = entry.getString("output")
            assertEquals("normalizeRecoveryCode(${input.orEmptyLabel()})", expected, normalizeRecoveryCode(input))
        }
    }

    @Test
    fun `Argon2id KEK derivation matches the web app's real implementation (WebCrypto vs Bouncy Castle)`() {
        val fixtures = loadFixtures().getJSONArray("kek")
        assertTrue("expected at least 2 kek vectors, got ${fixtures.length()}", fixtures.length() >= 2)

        for (i in 0 until fixtures.length()) {
            val entry = fixtures.getJSONObject(i)
            val input = entry.getString("input")
            val salt = Base64.getDecoder().decode(entry.getString("saltBase64"))
            val kdfJson = entry.getJSONObject("kdf")
            val params = KdfParams.Argon2id(
                salt = salt,
                memorySizeKiB = kdfJson.getInt("memorySize"),
                iterations = kdfJson.getInt("iterations"),
                parallelism = kdfJson.getInt("parallelism"),
                hashLength = kdfJson.getInt("hashLength"),
            )
            val expectedKek = Base64.getDecoder().decode(entry.getString("expectedKekBase64"))

            // deriveKEK normalizes internally on the web side (inside
            // unlockWithRecoveryCode); this fixture pins the KDF primitive
            // itself, so the input is normalized here explicitly first, same
            // as generate-golden-fixtures.test.js does when building it.
            val kek = deriveKEK(normalizeRecoveryCode(input), params)
            assertArrayEquals("KEK for input=\"$input\"", expectedKek, kek)
        }
    }

    private fun loadFixtures(): JSONObject {
        val text = requireNotNull(javaClass.getResourceAsStream("/spec/recovery-code-vectors.json")) {
            "Missing test resource /spec/recovery-code-vectors.json -- copy it from the web repo's spec/ directory"
        }.bufferedReader(Charsets.UTF_8).readText()
        return JSONTokener(text).nextValue() as JSONObject
    }

    private fun String?.orEmptyLabel(): String = this ?: "null"
}
