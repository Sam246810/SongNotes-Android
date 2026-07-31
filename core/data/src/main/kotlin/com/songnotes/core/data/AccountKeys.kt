package com.songnotes.core.data

import java.security.SecureRandom

/**
 * High-level account-key envelope operations, matching the desktop web app's
 * `src/crypto/accountKeys.js` port-for-port (see Envelope.kt's doc comment for the
 * wire shape). Kotlin only builds/reads v2 envelopes -- unlike the web app, the
 * Android client has never written a v1 envelope, so there's no legacy shape to
 * stay compatible with here.
 */
private const val RECOVERY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // excludes 0/O, 1/I/L etc.

data class AccountKeys(val dek: ByteArray, val envelope: EnvelopeV2, val recoveryCode: String)

fun createAccountKeys(accountPassword: String, recoveryCode: String = generateRecoveryCode()): AccountKeys {
    val dek = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val passWrap = buildWrap("pass", "passphrase", accountPassword, dek)
    val recoveryWrap = buildWrap("recovery", "recovery-code", recoveryCode, dek)
    val envelope = EnvelopeV2(
        dekId = generateDekId(),
        alg = "AES-256-GCM",
        wraps = listOf(passWrap, recoveryWrap),
        verifier = computeDekVerifier(dek),
    )
    return AccountKeys(dek, envelope, recoveryCode)
}

private fun buildWrap(id: String, type: String, secret: String, dek: ByteArray): WrapEntry {
    val params = KdfParams.Argon2id(salt = KdfParams.randomSalt())
    val kek = deriveKEK(secret, params)
    val (iv, ct) = wrapContentKey(kek, dek)
    return WrapEntry(id, type, params, iv, ct)
}

/** @throws IllegalStateException if `accountPassword` is wrong, or GCM tag failure if the envelope is tampered. */
fun unlockWithPassphrase(envelope: EnvelopeV2, accountPassword: String): ByteArray =
    unlockWithType(envelope, "passphrase", accountPassword)

/** @throws IllegalStateException if `recoveryCode` is wrong, or GCM tag failure if the envelope is tampered. */
fun unlockWithRecoveryCode(envelope: EnvelopeV2, recoveryCode: String): ByteArray =
    unlockWithType(envelope, "recovery-code", recoveryCode)

private fun unlockWithType(envelope: EnvelopeV2, type: String, secret: String): ByteArray {
    val wrap = envelope.wraps.find { it.type == type } ?: error("No \"$type\" wrap in this envelope")
    val kek = deriveKEK(secret, wrap.kdf)
    val dek = unwrapContentKey(kek, wrap.iv, wrap.ct)
    check(checkDekVerifier(dek, envelope.verifier)) {
        // Correct KDF inputs unwrapped *a* key (GCM tag matched), but it isn't the DEK this
        // envelope's verifier was computed for -- matches accountKeys.js's own defense-in-depth check.
        "DEK verifier mismatch after unwrap"
    }
    return dek
}

/** A high-entropy, easy-to-transcribe recovery code -- matches accountKeys.js's generateRecoveryCode exactly. */
fun generateRecoveryCode(): String {
    val bytes = ByteArray(20).also { SecureRandom().nextBytes(it) } // 160 bits of entropy
    val sb = StringBuilder()
    for (i in bytes.indices) {
        val idx = bytes[i].toInt() and 0xFF
        sb.append(RECOVERY_CODE_ALPHABET[idx % RECOVERY_CODE_ALPHABET.length])
        if ((i + 1) % 5 == 0 && i != bytes.lastIndex) sb.append('-')
    }
    return sb.toString()
}

/** A short random identifier stamped on the envelope -- matches accountKeys.js's generateDekId exactly. */
private fun generateDekId(): String {
    val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}
