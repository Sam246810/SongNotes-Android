package com.songnotes.core.data

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounds coverage for [validateKdfParams] / [KdfParams.fromJson] / [deriveKEK].
 *
 * These parameters arrive from the server -- `user_keys.envelope` is fetched and
 * parsed before this client has authenticated anything about its contents -- and
 * were previously handed to Argon2id unchecked. The concrete failure this guards
 * is not subtle: `memorySize: 4194304` asks Bouncy Castle for a 4 GiB allocation
 * on every unlock attempt, and unlocking is the only way back into the account,
 * so an OOM there is a permanent lockout on that device rather than a bad
 * password message.
 *
 * The other half of the contract matters just as much and is tested here too:
 * these bounds must **admit** weak-but-legitimate legacy parameters, because
 * WIRE-FORMAT-v2 3.3 requires a client to read a below-policy wrap in order to
 * upgrade it ([migrateWrapIfNeeded]). A "reject anything below current policy"
 * check here would turn every legacy PBKDF2 envelope into a permanent lockout --
 * the exact bug it would look like it was preventing.
 *
 * Limits are ported value-for-value from the web app's `src/crypto/kdf.js`
 * `KDF_LIMITS`; an envelope one client accepts the other must accept too, so
 * these assertions double as the cross-client wire contract.
 */
class KdfParamsValidationTest {

    private fun salt(size: Int = 16) = ByteArray(size) { it.toByte() }

    private fun argonJson(
        memorySize: Int = 65536,
        iterations: Int = 3,
        parallelism: Int = 1,
        hashLength: Int = 32,
        saltBytes: ByteArray = salt(),
    ) = JSONObject()
        .put("name", "Argon2id")
        .put("memorySize", memorySize)
        .put("iterations", iterations)
        .put("parallelism", parallelism)
        .put("hashLength", hashLength)
        .put("salt", Base64.getEncoder().encodeToString(saltBytes))

    // ---- The canonical wire values must always round-trip -------------------

    @Test
    fun `canonical Argon2id params from the wire format are accepted`() {
        val parsed = KdfParams.fromJson(argonJson()) as KdfParams.Argon2id
        assertEquals(65536, parsed.memorySizeKiB)
        assertEquals(3, parsed.iterations)
        assertEquals(1, parsed.parallelism)
        assertEquals(32, parsed.hashLength)
    }

    @Test
    fun `canonical legacy PBKDF2 params from the wire format are accepted`() {
        val json = JSONObject()
            .put("name", "PBKDF2")
            .put("hash", "SHA-256")
            .put("iterations", 600_000)
            .put("salt", Base64.getEncoder().encodeToString(salt()))
        val parsed = KdfParams.fromJson(json) as KdfParams.Pbkdf2
        assertEquals(600_000, parsed.iterations)
        assertEquals("SHA-256", parsed.hash)
    }

    @Test
    fun `missing optional fields fall back to canonical values rather than throwing`() {
        // Matches kdf.js's `params.x ?? DEFAULT_KDF_PARAMS.x`.
        val sparse = JSONObject()
            .put("name", "Argon2id")
            .put("salt", Base64.getEncoder().encodeToString(salt()))
        val parsed = KdfParams.fromJson(sparse) as KdfParams.Argon2id
        assertEquals(KdfParams.Argon2id.DEFAULT_MEMORY_KIB, parsed.memorySizeKiB)
        assertEquals(KdfParams.Argon2id.DEFAULT_ITERATIONS, parsed.iterations)
    }

    // ---- The DoS guards -----------------------------------------------------

    @Test
    fun `an envelope claiming 4 GiB of Argon2 memory is rejected, not attempted`() {
        val e = assertThrows(KdfParameterException::class.java) {
            KdfParams.fromJson(argonJson(memorySize = 4_194_304))
        }
        assertTrue(
            "message should name the offending parameter and its allowed range, got: ${e.message}",
            e.message!!.contains("memorySize=4194304") && e.message!!.contains("1024..262144"),
        )
    }

    @Test
    fun `a nine-digit Argon2 iteration count is rejected`() {
        assertThrows(KdfParameterException::class.java) {
            KdfParams.fromJson(argonJson(iterations = 100_000_000))
        }
    }

    @Test
    fun `an absurd PBKDF2 iteration count is rejected`() {
        val json = JSONObject()
            .put("name", "PBKDF2")
            .put("hash", "SHA-256")
            .put("iterations", 900_000_000)
            .put("salt", Base64.getEncoder().encodeToString(salt()))
        assertThrows(KdfParameterException::class.java) { KdfParams.fromJson(json) }
    }

    @Test
    fun `an out-of-range Argon2 parallelism is rejected`() {
        assertThrows(KdfParameterException::class.java) {
            KdfParams.fromJson(argonJson(parallelism = 4096))
        }
    }

    @Test
    fun `a hashLength that cannot key AES-256 is rejected`() {
        val e = assertThrows(KdfParameterException::class.java) {
            KdfParams.fromJson(argonJson(hashLength = 16))
        }
        assertTrue(e.message!!.contains("hashLength=16"))
    }

    @Test
    fun `zero and negative values are rejected rather than passed through`() {
        assertThrows(KdfParameterException::class.java) { KdfParams.fromJson(argonJson(iterations = 0)) }
        assertThrows(KdfParameterException::class.java) { KdfParams.fromJson(argonJson(memorySize = -1)) }
    }

    @Test
    fun `an unrecognized algorithm name is named as such, not silently treated as Argon2id`() {
        val json = JSONObject()
            .put("name", "scrypt")
            .put("salt", Base64.getEncoder().encodeToString(salt()))
        val e = assertThrows(KdfParameterException::class.java) { KdfParams.fromJson(json) }
        assertTrue(
            "an unknown algorithm should say so, not surface later as a wrong password",
            e.message!!.contains("Unsupported KDF algorithm") && e.message!!.contains("scrypt"),
        )
    }

    @Test
    fun `an unsupported PBKDF2 hash is rejected`() {
        val json = JSONObject()
            .put("name", "PBKDF2")
            .put("hash", "MD5")
            .put("iterations", 600_000)
            .put("salt", Base64.getEncoder().encodeToString(salt()))
        assertThrows(KdfParameterException::class.java) { KdfParams.fromJson(json) }
    }

    // ---- These are DoS guards, NOT a security floor -------------------------

    @Test
    fun `a below-policy PBKDF2 wrap stays readable so it can be upgraded`() {
        // WIRE-FORMAT-v2 3.3: a client must be able to READ a legacy wrap in
        // order to rewrap it at current policy. Rejecting this would convert
        // every legacy envelope into a permanent lockout.
        val legacy = KdfParams.Pbkdf2(salt = salt(), iterations = 1000)
        validateKdfParams(legacy)
        assertTrue(legacy.isBelowCurrentPolicy())
        assertEquals(32, deriveKEK("correct horse battery staple", legacy).size)
    }

    @Test
    fun `a below-default Argon2 cost stays readable`() {
        val weak = KdfParams.Argon2id(salt = salt(), memorySizeKiB = 1024, iterations = 1)
        validateKdfParams(weak)
        assertEquals(32, deriveKEK("correct horse battery staple", weak).size)
    }

    // ---- Salt length is a derive-time check, matching kdf.js ----------------

    @Test
    fun `a too-short salt is rejected at derive time`() {
        val e = assertThrows(KdfParameterException::class.java) {
            deriveKEK("pw", KdfParams.Argon2id(salt = salt(4)))
        }
        assertTrue(e.message!!.contains("saltBytes"))
    }

    @Test
    fun `a too-short salt still parses, so envelopes stay inspectable`() {
        // Deliberate: kdf.js checks salt length in deriveKEK, not in
        // validateKdfParams, and WrapEntry round-trip coverage depends on
        // parsing staying tolerant here.
        validateKdfParams(KdfParams.Argon2id(salt = salt(4)))
    }

    // ---- deriveKEK is the choke point, not just fromJson --------------------

    @Test
    fun `deriveKEK rejects hostile params even when built directly, bypassing fromJson`() {
        // KdfParams is a public data class; validating only at the JSON boundary
        // would leave this path open.
        assertThrows(KdfParameterException::class.java) {
            deriveKEK("pw", KdfParams.Argon2id(salt = salt(), memorySizeKiB = 4_194_304))
        }
    }

    @Test
    fun `a rejected derivation throws before allocating, so it fails fast`() {
        val startedAt = System.nanoTime()
        assertThrows(KdfParameterException::class.java) {
            deriveKEK("pw", KdfParams.Argon2id(salt = salt(), memorySizeKiB = 4_194_304, iterations = 16))
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("validation should reject without doing any KDF work, took ${elapsedMs}ms", elapsedMs < 1000)
    }
}
