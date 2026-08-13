package com.songnotes.core.data

import java.security.SecureRandom

/**
 * High-level account-key envelope operations, matching the desktop web app's
 * `src/crypto/accountKeys.js` port-for-port (see Envelope.kt's doc comment for the
 * wire shape). Kotlin only builds/reads v2 envelopes -- unlike the web app, the
 * Android client has never written a v1 envelope, so there's no legacy shape to
 * stay compatible with here. RECOVERY_CODE_ALPHABET lives in RecoveryCode.kt,
 * shared with normalizeRecoveryCode.
 */

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

/**
 * @throws IllegalStateException if `recoveryCode` is wrong, or GCM tag failure if the envelope is tampered.
 * Normalizes the input first (see RecoveryCode.kt) so a code retyped lowercase,
 * without hyphens, or with stray whitespace still unlocks -- every recovery-code
 * unlock on Android goes through here, so this is the single call site normalization
 * needs to live behind.
 */
fun unlockWithRecoveryCode(envelope: EnvelopeV2, recoveryCode: String): ByteArray =
    unlockWithType(envelope, "recovery-code", normalizeRecoveryCode(recoveryCode))

private fun unlockWithType(envelope: EnvelopeV2, type: String, secret: String): ByteArray {
    val wrap = envelope.wraps.find { it.type == type } ?: error("No \"$type\" wrap in this envelope")
    val kdf = requireNotNull(wrap.kdf) { "\"$type\" wrap has no kdf -- not a passphrase/recovery-code wrap" }
    val kek = deriveKEK(secret, kdf)
    val dek = unwrapContentKey(kek, wrap.iv, wrap.ct)
    check(checkDekVerifier(dek, envelope.verifier)) {
        // Correct KDF inputs unwrapped *a* key (GCM tag matched), but it isn't the DEK this
        // envelope's verifier was computed for -- matches accountKeys.js's own defense-in-depth check.
        "DEK verifier mismatch after unwrap"
    }
    return dek
}

/**
 * A high-entropy, easy-to-transcribe recovery code -- matches accountKeys.js's
 * generateRecoveryCode exactly. 20 chars from a 32-symbol alphabet is ~100 bits
 * of entropy (log2(32)*20), not 160 -- 160 would be the entropy of the 20 raw
 * random *bytes* this draws from, before the `% alphabet.length` reduction
 * discards the rest of each byte.
 */
fun generateRecoveryCode(): String {
    val bytes = ByteArray(20).also { SecureRandom().nextBytes(it) }
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

/**
 * After recovering the DEK via the recovery code, set a new account password for
 * it -- matches accountKeys.js's rewrapWithNewPassphrase exactly (v2-only here;
 * Kotlin never reads v1). Replaces only the `passphrase` wrap; dekId, verifier,
 * and the recovery wrap are all carried over unchanged, so zero songs need
 * re-encryption and the original recovery code keeps working afterward.
 */
fun rewrapWithNewPassphrase(envelope: EnvelopeV2, dek: ByteArray, newAccountPassword: String): EnvelopeV2 {
    val passWrap = buildWrap("pass", "passphrase", newAccountPassword, dek)
    return envelope.copy(wraps = envelope.wraps.map { if (it.type == "passphrase") passWrap else it })
}

data class MigrateResult(val envelope: EnvelopeV2, val migrated: Boolean)

/**
 * Re-wraps a single unlock method (passphrase or recovery code) onto the current
 * KDF policy (Argon2id) if it was still on PBKDF2 -- called with the secret + DEK
 * already in hand from a *successful* unlock, so this never prompts for anything
 * extra. Matches accountKeys.js's migrateWrapIfNeeded; the web app's v1-envelope
 * upgrade branch has no Kotlin equivalent since there's no v1 reader here.
 */
fun migrateWrapIfNeeded(envelope: EnvelopeV2, type: String, secret: String, dek: ByteArray): MigrateResult {
    val wrap = envelope.wraps.find { it.type == type } ?: return MigrateResult(envelope, false)
    val kdf = wrap.kdf ?: return MigrateResult(envelope, false)
    if (!kdf.isBelowCurrentPolicy()) return MigrateResult(envelope, false)

    val newWrap = buildWrap(wrap.id, type, secret, dek)
    val newEnvelope = envelope.copy(wraps = envelope.wraps.map { if (it.type == type) newWrap else it })
    return MigrateResult(newEnvelope, true)
}

data class RegeneratedRecovery(val envelope: EnvelopeV2, val recoveryCode: String)

/**
 * Mints a brand-new recovery code and replaces ONLY the recovery wrap -- for an
 * account whose owner never saw/saved theirs, or simply wants to rotate it.
 * Matches accountKeys.js's regenerateRecoveryWrap. The passphrase wrap, dekId,
 * and verifier are untouched, and the DEK itself never changes, so no song is
 * re-encrypted and signing in with the account password keeps working exactly as
 * before. Requires the DEK already in hand (caller must be unlocked) -- this
 * never derives from the OLD recovery code, so it works even if that one is lost.
 */
fun regenerateRecoveryWrap(envelope: EnvelopeV2, dek: ByteArray): RegeneratedRecovery {
    val recoveryCode = generateRecoveryCode()
    val recoveryWrap = buildWrap("recovery", "recovery-code", recoveryCode, dek)
    val newEnvelope = envelope.copy(wraps = envelope.wraps.map { if (it.type == "recovery-code") recoveryWrap else it })
    return RegeneratedRecovery(newEnvelope, recoveryCode)
}
