package com.songnotes.core.data

import java.io.File
import java.util.Base64
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation test vectors for envelope v2 (Phase 6's own "Done" criterion:
 * "a web-app envelope decrypts in Kotlin and vice versa"). Two directions:
 *
 * 1. [webEnvelopeDecryptsInKotlin] reads `/spec/envelope-v2.json`, a REAL envelope
 *    (real random salts/IVs, not synthetic) produced by the desktop repo's
 *    `src/test/generate-golden-fixtures.test.js` via its own `createAccountKeys`,
 *    and asserts this module's `unlockWithPassphrase`/`unlockWithRecoveryCode`
 *    recover byte-identical DEK material from it.
 * 2. [writesAndroidEnvelopeForWebToDecrypt] builds an equally-real envelope with
 *    this module's own `createAccountKeys`, sanity-checks it unlocks correctly
 *    right here, and writes it to `spec/envelope-v2-from-android.json` (both this
 *    repo's own test-resources copy, so re-running this test is self-contained,
 *    and the desktop repo's `spec/` directory, so its own reverse-direction test
 *    in `generate-golden-fixtures.test.js` picks it up) -- same "write a fixture
 *    as a side effect of a test run, then commit it" convention the JS side
 *    already established for the chord/lyrics fixtures.
 */
class EnvelopeV2GoldenFixtureTest {

    @Test
    fun `web envelope decrypts in Kotlin`() {
        val json = loadResourceJson("/spec/envelope-v2.json")
        val passphrase = json.getString("passphrase")
        val recoveryCode = json.getString("recoveryCode")
        val expectedDek = Base64.getDecoder().decode(json.getString("expectedDekBase64"))
        val envelope = EnvelopeV2.fromJson(json.getJSONObject("envelope"))

        val viaPassphrase = unlockWithPassphrase(envelope, passphrase)
        val viaRecovery = unlockWithRecoveryCode(envelope, recoveryCode)

        assertArrayEquals("passphrase-unlocked DEK should match the JS fixture's expected DEK", expectedDek, viaPassphrase)
        assertArrayEquals("recovery-unlocked DEK should match the JS fixture's expected DEK", expectedDek, viaRecovery)
    }

    @Test
    fun `writes an Android-built envelope for the web app to decrypt`() {
        val passphrase = "kotlin correct horse battery staple"
        val recoveryCode = "KLMNP-QRSTU-VWXYZ-23456-789AB"
        val keys = createAccountKeys(passphrase, recoveryCode)

        // Sanity-check the fixture round-trips in this language before committing it.
        assertArrayEquals(keys.dek, unlockWithPassphrase(keys.envelope, passphrase))
        assertArrayEquals(keys.dek, unlockWithRecoveryCode(keys.envelope, recoveryCode))
        assertTrue("recovery code should be non-empty", recoveryCode.isNotEmpty())

        val fixture = JSONObject()
            .put("passphrase", passphrase)
            .put("recoveryCode", recoveryCode)
            .put("expectedDekBase64", Base64.getEncoder().encodeToString(keys.dek))
            .put("envelope", keys.envelope.toJson())
        val text = fixture.toString(2) + "\n"

        writeIfParentExists(File("src/test/resources/spec/envelope-v2-from-android.json"), text)
        // Also drop a copy directly into the desktop repo's spec/ dir, if it's checked
        // out as a sibling of this repo (the layout every prior fixture-porting pass in
        // this session has assumed) -- matches "committed to BOTH repos" from the plan.
        writeIfParentExists(File("../../../SongNotes/spec/envelope-v2-from-android.json"), text)
    }

    private fun writeIfParentExists(file: File, text: String) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeText(text)
    }

    private fun loadResourceJson(resourceName: String): JSONObject {
        val text = requireNotNull(javaClass.getResourceAsStream(resourceName)) {
            "Missing test resource $resourceName"
        }.bufferedReader(Charsets.UTF_8).readText()
        return JSONTokener(text).nextValue() as JSONObject
    }
}
