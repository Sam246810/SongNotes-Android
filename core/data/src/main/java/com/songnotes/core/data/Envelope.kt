package com.songnotes.core.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account-level key envelope v2, matching the desktop web app's
 * `src/crypto/accountKeys.js` / `src/crypto/envelope.js` port-for-port -- see
 * `docs/WIRE-FORMAT-v2.md` / `docs/PLAN.md`'s "Envelope v2" section for the shape
 * this is contractually pinned to:
 *
 * ```json
 * { "v": 2, "dekId": "...", "alg": "AES-256-GCM",
 *   "wraps": [ { "id":"pass", "type":"passphrase", "kdf":{...}, "iv":"...", "ct":"..." },
 *              { "id":"recovery", "type":"recovery-code", "kdf":{...}, "iv":"...", "ct":"..." } ],
 *   "verifier": { "iv":"...", "ct":"AES-GCM(DEK,'songnotes-dek-check-v2')" } }
 * ```
 *
 * The DEK itself is never modeled here as anything but a raw [ByteArray] -- unlike
 * the web app's non-extractable `CryptoKey`, the JVM has no equivalent opaque-key
 * primitive worth reaching for at this scale, and a raw key is exactly what
 * `javax.crypto.Cipher` wants anyway.
 */
private const val IV_BYTES = 12 // 96-bit, matches envelope.js's IV_BYTES
private const val GCM_TAG_BITS = 128
const val DEK_VERIFIER_PLAINTEXT = "songnotes-dek-check-v2"

data class WrapEntry(val id: String, val type: String, val kdf: KdfParams, val iv: ByteArray, val ct: ByteArray) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("type", type)
        .put("kdf", kdf.toJson())
        .put("iv", Base64.getEncoder().encodeToString(iv))
        .put("ct", Base64.getEncoder().encodeToString(ct))

    companion object {
        fun fromJson(json: JSONObject): WrapEntry = WrapEntry(
            id = json.getString("id"),
            type = json.getString("type"),
            kdf = KdfParams.fromJson(json.getJSONObject("kdf")),
            iv = Base64.getDecoder().decode(json.getString("iv")),
            ct = Base64.getDecoder().decode(json.getString("ct")),
        )
    }
}

data class DekVerifier(val iv: ByteArray, val ct: ByteArray) {
    fun toJson(): JSONObject = JSONObject()
        .put("iv", Base64.getEncoder().encodeToString(iv))
        .put("ct", Base64.getEncoder().encodeToString(ct))

    companion object {
        fun fromJson(json: JSONObject): DekVerifier =
            DekVerifier(Base64.getDecoder().decode(json.getString("iv")), Base64.getDecoder().decode(json.getString("ct")))
    }
}

data class EnvelopeV2(val dekId: String, val alg: String, val wraps: List<WrapEntry>, val verifier: DekVerifier) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", 2)
        .put("dekId", dekId)
        .put("alg", alg)
        .put("wraps", JSONArray(wraps.map { it.toJson() }))
        .put("verifier", verifier.toJson())

    companion object {
        /** Parses a v2 envelope. Throws if `json.getInt("v") != 2` -- callers check the version first. */
        fun fromJson(json: JSONObject): EnvelopeV2 {
            require(json.getInt("v") == 2) { "Not a v2 envelope (v=${json.optInt("v")})" }
            val wraps = json.getJSONArray("wraps").let { arr ->
                (0 until arr.length()).map { WrapEntry.fromJson(arr.getJSONObject(it)) }
            }
            return EnvelopeV2(
                dekId = json.getString("dekId"),
                alg = json.getString("alg"),
                wraps = wraps,
                verifier = DekVerifier.fromJson(json.getJSONObject("verifier")),
            )
        }
    }
}

/** AES-GCM-encrypts `plaintext` under `key` with a fresh random IV -- used for the DEK verifier. */
fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
    val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
    return iv to cipher.doFinal(plaintext)
}

/** AES-GCM-decrypts `ct` under `key`/`iv`. Throws (AEADBadTagException) if `key` is wrong or `ct` is tampered. */
fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
    return cipher.doFinal(ct)
}

/**
 * Wraps (encrypts) a raw content key with a KEK -- equivalent to WebCrypto's
 * `wrapKey('raw', contentKey, kek, {name:'AES-GCM', iv})`, which is exactly an
 * AES-GCM encryption of the content key's raw bytes as plaintext, no extra framing.
 */
fun wrapContentKey(kek: ByteArray, contentKey: ByteArray): Pair<ByteArray, ByteArray> = aesGcmEncrypt(kek, contentKey)

/** Unwraps a content key previously wrapped with [wrapContentKey]. Throws if `kek` is wrong. */
fun unwrapContentKey(kek: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray = aesGcmDecrypt(kek, iv, ct)

fun computeDekVerifier(dek: ByteArray): DekVerifier {
    val (iv, ct) = aesGcmEncrypt(dek, DEK_VERIFIER_PLAINTEXT.toByteArray(Charsets.UTF_8))
    return DekVerifier(iv, ct)
}

fun checkDekVerifier(dek: ByteArray, verifier: DekVerifier): Boolean = try {
    String(aesGcmDecrypt(dek, verifier.iv, verifier.ct), Charsets.UTF_8) == DEK_VERIFIER_PLAINTEXT
} catch (e: Exception) {
    false // wrong key -> GCM auth tag failure, not a real error (matches envelope.js's checkDekVerifier)
}
