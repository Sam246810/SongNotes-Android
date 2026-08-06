package com.songnotes.core.data

import java.security.SecureRandom
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matches crypto.test.js's "envelope: content encryption" describe block. */
class ContentEnvelopeTest {

    private fun randomDek(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `round-trips a JSON value through encrypt-decrypt`() {
        val dek = randomDek()
        val song = JSONObject().put("title", "My Song").put("lines", "Am hello")
        val envelope = encryptContentJson(dek, song.toString())

        assertEquals(1, envelope.v)
        assertEquals("AES-GCM", envelope.alg)

        val decrypted = JSONObject(decryptContentJson(dek, envelope))
        assertEquals("My Song", decrypted.getString("title"))
    }

    @Test
    fun `never leaks the plaintext into the envelope`() {
        val dek = randomDek()
        val secretTitle = "super-secret-title-xyz"
        val envelope = encryptContentJson(dek, JSONObject().put("title", secretTitle).toString())
        assertFalse(envelope.toJson().toString().contains(secretTitle))
    }

    @Test
    fun `fails to decrypt with the wrong key`() {
        val dek1 = randomDek()
        val dek2 = randomDek()
        val envelope = encryptContentJson(dek1, JSONObject().put("data", "secret").toString())
        assertThrows(Exception::class.java) { decryptContentJson(dek2, envelope) }
    }

    @Test
    fun `never reuses an IV across many encryptions with the same key`() {
        val dek = randomDek()
        val ivs = mutableSetOf<String>()
        repeat(50) {
            val envelope = encryptContentJson(dek, JSONObject().put("i", it).toString())
            assertTrue(ivs.add(envelope.iv))
        }
        assertEquals(50, ivs.size)
    }

    @Test
    fun `JSON round-trip preserves v-alg-iv-ct exactly`() {
        val dek = randomDek()
        val envelope = encryptContentJson(dek, JSONObject().put("x", 1).toString())
        val parsed = ContentEnvelope.fromJson(envelope.toJson())
        assertEquals(envelope, parsed)
    }
}
