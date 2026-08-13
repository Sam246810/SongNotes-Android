package com.songnotes.core.data

import java.text.Normalizer

/**
 * Recovery-code normalization, matching the desktop web app's
 * `src/crypto/recoveryCode.js` port-for-port. A recovery code is a KDF input, not
 * just display text: the exact string typed by the user is what gets
 * Argon2id-derived into a KEK, so "the same code" retyped lowercase, without
 * hyphens, or with stray whitespace must still derive the identical key on both
 * platforms -- see docs/WIRE-FORMAT-v2.md §3.1.
 *
 * [normalizeRecoveryCode] is deliberately total (never throws) and dumb -- no
 * length check, no confusable-character correction -- so it stays safe to run on
 * every unlock attempt, correct or not, and so the committed golden fixtures
 * (spec/recovery-code-vectors.json) fully pin its behavior across both languages.
 * UI-facing hints belong in [describeRecoveryCodeInput], not here.
 */

/**
 * Same alphabet as generateRecoveryCode (AccountKeys.kt) -- excludes 0, 1, I, O
 * (not L: the string reads ...GHJKLMN..., L is a valid, generatable character).
 */
const val RECOVERY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

private val ALPHABET_SET = RECOVERY_CODE_ALPHABET.toSet()

/**
 * Canonicalizes user-entered recovery-code text before it's used as a KDF input:
 * NFKC-normalize (folds full-width/compatibility variants like 'Ａ' to 'A'),
 * uppercase using [uppercase] (locale-invariant -- deliberately NOT the deprecated
 * `toUpperCase()`, which is Turkish-locale-sensitive and would map 'i' to a
 * dotless capital I outside the alphabet on a device set to that locale), strip
 * every character not in the alphabet (drops hyphens, whitespace, anything else),
 * then regroup into 5-character chunks joined by '-' with NO trailing separator.
 *
 * Idempotent and a no-op on any canonically-generated code, so this can never
 * break a code that currently works.
 */
fun normalizeRecoveryCode(input: String?): String {
    if (input.isNullOrEmpty()) return ""
    val stripped = Normalizer.normalize(input, Normalizer.Form.NFKC)
        .uppercase()
        .filter { it in ALPHABET_SET }
    if (stripped.isEmpty()) return ""
    return stripped.chunked(5).joinToString("-")
}

/** UI-only diagnostics for a not-yet-submitted recovery-code input -- never used on the derive path. */
data class RecoveryCodeInputDescription(val normalized: String, val normalizedLength: Int, val confusables: List<Char>)

/**
 * Meant to warn *before* an ~1s Argon2id attempt, not to block one. Only flags
 * characters actually absent from the alphabet -- L is NOT one of them.
 */
fun describeRecoveryCodeInput(input: String?): RecoveryCodeInputDescription {
    val normalized = normalizeRecoveryCode(input)
    val upper = Normalizer.normalize(input.orEmpty(), Normalizer.Form.NFKC).uppercase()
    val confusables = upper.toSet().filter { it in setOf('I', 'O', '0', '1') }
    return RecoveryCodeInputDescription(normalized, normalized.count { it != '-' }, confusables)
}
