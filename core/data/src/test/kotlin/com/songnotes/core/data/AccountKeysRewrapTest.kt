package com.songnotes.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Kotlin-side coverage for the password-change/recovery primitives ported from
 * the web app's accountKeys.js (rewrapWithNewPassphrase, migrateWrapIfNeeded,
 * regenerateRecoveryWrap) -- mirrors src/test/crypto.test.js's equivalent cases
 * so the two implementations are pinned to the same behavior, not just the same
 * envelope shape (that part is EnvelopeV2GoldenFixtureTest's job).
 */
class AccountKeysRewrapTest {

    @Test
    fun `rewrapWithNewPassphrase lets a new passphrase unlock the same DEK, old one no longer works`() {
        val recoveryCode = generateRecoveryCode()
        val keys = createAccountKeys("old-passphrase", recoveryCode)

        val newEnvelope = rewrapWithNewPassphrase(keys.envelope, keys.dek, "new-passphrase")

        assertArrayEquals(keys.dek, unlockWithPassphrase(newEnvelope, "new-passphrase"))
        assertThrows(Exception::class.java) { unlockWithPassphrase(newEnvelope, "old-passphrase") }
        // Recovery code still unlocks the same DEK -- untouched by the passphrase reset.
        assertArrayEquals(keys.dek, unlockWithRecoveryCode(newEnvelope, recoveryCode))
        // dekId and verifier are unchanged.
        assertEquals(keys.envelope.dekId, newEnvelope.dekId)
        assertArrayEquals(keys.envelope.verifier.ct, newEnvelope.verifier.ct)
    }

    @Test
    fun `migrateWrapIfNeeded rewraps a PBKDF2 passphrase wrap to Argon2id, leaves an up-to-date one alone`() {
        val passphrase = "a-passphrase"
        val keys = createAccountKeys(passphrase, generateRecoveryCode())

        // Downgrade the passphrase wrap to PBKDF2 to simulate a legacy envelope.
        val pbkdf2Params = KdfParams.Pbkdf2(salt = KdfParams.randomSalt())
        val pbkdf2Kek = deriveKEK(passphrase, pbkdf2Params)
        val (iv, ct) = wrapContentKey(pbkdf2Kek, keys.dek)
        val downgradedWrap = WrapEntry("pass", "passphrase", pbkdf2Params, iv, ct)
        val downgraded = keys.envelope.copy(wraps = keys.envelope.wraps.map { if (it.type == "passphrase") downgradedWrap else it })

        // Sanity: the downgraded envelope really does unlock via PBKDF2.
        assertArrayEquals(keys.dek, unlockWithPassphrase(downgraded, passphrase))

        val (migratedEnvelope, migrated) = migrateWrapIfNeeded(downgraded, "passphrase", passphrase, keys.dek)
        assertEquals(true, migrated)
        val migratedWrap = migratedEnvelope.wraps.first { it.type == "passphrase" }
        assertEquals(true, migratedWrap.kdf is KdfParams.Argon2id)
        assertArrayEquals(keys.dek, unlockWithPassphrase(migratedEnvelope, passphrase))

        // Already on Argon2id -- no-op.
        val (_, migratedAgain) = migrateWrapIfNeeded(migratedEnvelope, "passphrase", passphrase, keys.dek)
        assertEquals(false, migratedAgain)
    }

    @Test
    fun `regenerateRecoveryWrap mints a new code, old code stops working, passphrase untouched`() {
        val oldCode = generateRecoveryCode()
        val keys = createAccountKeys("a-passphrase", oldCode)

        val (newEnvelope, newCode) = regenerateRecoveryWrap(keys.envelope, keys.dek)
        assertNotEquals(oldCode, newCode)

        // dekId and verifier are unchanged -- this is a same-DEK operation, not a rotation.
        assertEquals(keys.envelope.dekId, newEnvelope.dekId)
        assertArrayEquals(keys.envelope.verifier.ct, newEnvelope.verifier.ct)

        assertArrayEquals(keys.dek, unlockWithRecoveryCode(newEnvelope, newCode))
        assertThrows(Exception::class.java) { unlockWithRecoveryCode(newEnvelope, oldCode) }
        // Passphrase wrap is completely untouched.
        assertArrayEquals(keys.dek, unlockWithPassphrase(newEnvelope, "a-passphrase"))
    }
}
