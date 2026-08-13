package com.songnotes.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors src/test/recoveryCode.test.js's cases exactly -- normalizeRecoveryCode
 * must behave identically on both platforms since it's a KDF input, not just
 * display formatting.
 */
class RecoveryCodeTest {

    @Test
    fun `is a no-op on a canonically-generated code`() {
        repeat(20) {
            val code = generateRecoveryCode()
            assertEquals(code, normalizeRecoveryCode(code))
        }
    }

    @Test
    fun `uppercases lowercase input`() {
        assertEquals("ABCDE-FGHJK-LMNPQ-RSTUV", normalizeRecoveryCode("abcde-fghjk-lmnpq-rstuv"))
    }

    @Test
    fun `strips hyphens and re-inserts them canonically, with no trailing separator`() {
        assertEquals("ABCDE-FGHJK-LMNPQ-RSTUV", normalizeRecoveryCode("abcdefghjklmnpqrstuv"))
        // 25 chars (divisible by 5) -- must NOT get a trailing hyphen
        assertEquals("ABCDE-FGHJK-LMNPQ-RSTUV-WXYZ2", normalizeRecoveryCode("ABCDE-FGHJK-LMNPQ-RSTUV-WXYZ2"))
    }

    @Test
    fun `strips whitespace of all kinds`() {
        assertEquals("ABCDE-FGHJK-LMNPQ-RSTUV", normalizeRecoveryCode(" ABCDE FGHJK\tLMNPQ\nRSTUV "))
    }

    @Test
    fun `strips characters outside the alphabet (0,1,I,O) but keeps L, which is valid`() {
        assertEquals("ABCDE", normalizeRecoveryCode("ABC0DE-IO1"))
        assertEquals("ABCL2", normalizeRecoveryCode("ABCL2"))
    }

    @Test
    fun `NFKC-folds full-width characters`() {
        assertEquals("ABCDE", normalizeRecoveryCode("ＡＢＣＤＥ")) // full-width ABCDE
    }

    @Test
    fun `handles short input without a trailing separator`() {
        assertEquals("ABCDE-F", normalizeRecoveryCode("ABCDEF"))
    }

    @Test
    fun `is total -- empty and garbage input never throw`() {
        assertEquals("", normalizeRecoveryCode(""))
        assertEquals("", normalizeRecoveryCode("----"))
        assertEquals("", normalizeRecoveryCode(null))
    }

    @Test
    fun `every character of the alphabet round-trips`() {
        assertEquals(
            normalizeRecoveryCode(RECOVERY_CODE_ALPHABET),
            normalizeRecoveryCode(RECOVERY_CODE_ALPHABET.lowercase()),
        )
    }

    @Test
    fun `describeRecoveryCodeInput flags confusables without blocking, L is not confusable`() {
        val description = describeRecoveryCodeInput("ABC0DE-IOL1")
        assertEquals(setOf('0', '1', 'I', 'O'), description.confusables.toSet())
    }

    @Test
    fun `describeRecoveryCodeInput reports no confusables for a clean generated code`() {
        assertTrue(describeRecoveryCodeInput(generateRecoveryCode()).confusables.isEmpty())
    }
}
