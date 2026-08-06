package com.songnotes.core.data

import java.util.Base64
import org.json.JSONObject

/**
 * The simple per-song content envelope, matching the desktop web app's
 * `src/crypto/envelope.js` `encryptJSON`/`decryptJSON` exactly: `{v, alg, iv, ct}`.
 * Deliberately NOT [EnvelopeV2] -- that's the account-key envelope (`wraps[]`,
 * `dekId`, `verifier`) stored in `user_keys.envelope`; this is what actually
 * encrypts a song's `content` column in the `songs` table, directly under the
 * raw DEK bytes, no wrapping involved (the DEK is used directly as the AES-GCM
 * key here, same as `songsRepository.js`'s `_buildRow` calling `encryptJSON(dek,
 * ...)` with the account DEK itself, not a per-song key).
 */
private const val CONTENT_ENVELOPE_VERSION = 1

data class ContentEnvelope(val v: Int, val alg: String, val iv: String, val ct: String) {
    fun toJson(): JSONObject = JSONObject().put("v", v).put("alg", alg).put("iv", iv).put("ct", ct)

    companion object {
        fun fromJson(json: JSONObject): ContentEnvelope =
            ContentEnvelope(json.getInt("v"), json.getString("alg"), json.getString("iv"), json.getString("ct"))
    }
}

/** Encrypts a JSON string (e.g. `someJsonObject.toString()`) with the raw DEK bytes. */
fun encryptContentJson(dek: ByteArray, json: String): ContentEnvelope {
    val (iv, ct) = aesGcmEncrypt(dek, json.toByteArray(Charsets.UTF_8))
    return ContentEnvelope(
        v = CONTENT_ENVELOPE_VERSION,
        alg = "AES-GCM",
        iv = Base64.getEncoder().encodeToString(iv),
        ct = Base64.getEncoder().encodeToString(ct),
    )
}

/** Decrypts a [ContentEnvelope] back to its plaintext JSON string. Throws if `dek` is wrong or `envelope` is tampered. */
fun decryptContentJson(dek: ByteArray, envelope: ContentEnvelope): String {
    val iv = Base64.getDecoder().decode(envelope.iv)
    val ct = Base64.getDecoder().decode(envelope.ct)
    return String(aesGcmDecrypt(dek, iv, ct), Charsets.UTF_8)
}
