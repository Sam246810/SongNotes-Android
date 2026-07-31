package com.songnotes.core.data

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON round-trip coverage for [WrapEntry]'s nullable `kdf` -- added alongside
 * [DeviceWrap]'s `"device"`-type wrap, which has no KDF params (the KEK is an
 * Android Keystore key, not derived from a human secret). The
 * AndroidKeyStore/BiometricPrompt side of a device wrap can't run on plain
 * JVM unit tests (see `DiagnosticsScreen.kt`'s on-device smoke test for that),
 * but the envelope JSON shape it produces is exactly the kind of thing a
 * fixture-free unit test should pin down directly.
 */
class WrapEntryTest {

    @Test
    fun `a passphrase wrap's kdf survives JSON round-trip`() {
        val wrap = WrapEntry(
            id = "pass",
            type = "passphrase",
            kdf = KdfParams.Argon2id(salt = byteArrayOf(1, 2, 3, 4)),
            iv = byteArrayOf(5, 6, 7),
            ct = byteArrayOf(8, 9, 10),
        )
        val json = wrap.toJson()
        assertTrue("kdf key should be present for a passphrase wrap", json.has("kdf"))

        val parsed = WrapEntry.fromJson(json)
        assertEquals(wrap.id, parsed.id)
        assertEquals(wrap.type, parsed.type)
        assertTrue(parsed.kdf is KdfParams.Argon2id)
        assertArrayEquals(wrap.iv, parsed.iv)
        assertArrayEquals(wrap.ct, parsed.ct)
    }

    @Test
    fun `a device wrap's null kdf omits the key entirely and survives round-trip`() {
        val wrap = WrapEntry(id = "device", type = DeviceWrap.WRAP_TYPE, kdf = null, iv = byteArrayOf(1), ct = byteArrayOf(2, 3))
        val json = wrap.toJson()
        assertFalse("kdf key should be absent for a device wrap, not present-as-null", json.has("kdf"))

        val parsed = WrapEntry.fromJson(json)
        assertNull(parsed.kdf)
        assertEquals("device", parsed.type)
        assertArrayEquals(wrap.iv, parsed.iv)
        assertArrayEquals(wrap.ct, parsed.ct)
    }

    @Test
    fun `an envelope with a mix of passphrase, recovery, and device wraps round-trips`() {
        val envelope = EnvelopeV2(
            dekId = "abc123",
            alg = "AES-256-GCM",
            wraps = listOf(
                WrapEntry("pass", "passphrase", KdfParams.Argon2id(salt = byteArrayOf(1)), byteArrayOf(2), byteArrayOf(3)),
                WrapEntry("recovery", "recovery-code", KdfParams.Argon2id(salt = byteArrayOf(4)), byteArrayOf(5), byteArrayOf(6)),
                WrapEntry("device", DeviceWrap.WRAP_TYPE, null, byteArrayOf(7), byteArrayOf(8)),
            ),
            verifier = DekVerifier(byteArrayOf(9), byteArrayOf(10)),
        )
        val parsed = EnvelopeV2.fromJson(envelope.toJson())
        assertEquals(3, parsed.wraps.size)
        assertEquals(setOf("passphrase", "recovery-code", "device"), parsed.wraps.map { it.type }.toSet())
        assertNull(parsed.wraps.first { it.type == "device" }.kdf)
    }
}
