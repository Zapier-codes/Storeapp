package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.SelfInstallRules
import com.vythera.vyxelapps.api.SignerComparison
import com.vythera.vyxelapps.api.StatusVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf h.iii.zi. Written, NOT run (standing operator instruction: no testing, no build). Expected values come
 * from the rules in `SelfInstall.kt` and PackageInstaller's documented STATUS_* numbers, not from running the code.
 */
class SelfInstallRulesTest {

    private val keyA = "ab".repeat(32)
    private val keyB = "cd".repeat(32)
    private val rules = SelfInstallRules

    @Test
    fun `the same certificate in any case or separator is the same signer`() {
        val colon = keyA.uppercase().chunked(2).joinToString(":")
        assertEquals(SignerComparison.Same, rules.compareSigners(listOf(keyA), listOf(colon)))
        assertEquals(SignerComparison.Same, rules.compareSigners(listOf("sha256:$keyA"), listOf(keyA.uppercase())))
    }

    @Test
    fun `an unrelated certificate is Different and names both sets`() {
        val r = rules.compareSigners(listOf(keyA), listOf(keyB))
        assertTrue(r is SignerComparison.Different)
        r as SignerComparison.Different
        assertEquals(setOf(keyA), r.installed)
        assertEquals(setOf(keyB), r.candidate)
    }

    @Test
    fun `sets that share one certificate are Same so a key rotation is not blocked`() {
        assertEquals(SignerComparison.Same, rules.compareSigners(listOf(keyA, keyB), listOf(keyB)))
    }

    @Test
    fun `an unreadable or malformed certificate list is Unknown, never Same`() {
        assertTrue(rules.compareSigners(null, listOf(keyA)) is SignerComparison.Unknown)
        assertTrue(rules.compareSigners(listOf(keyA), null) is SignerComparison.Unknown)
        assertTrue(rules.compareSigners(emptyList(), emptyList()) is SignerComparison.Unknown)
        assertTrue(rules.compareSigners(listOf("not-hex"), listOf("not-hex")) is SignerComparison.Unknown)
        assertTrue(rules.compareSigners(listOf(keyA.take(62)), listOf(keyA.take(62))) is SignerComparison.Unknown)
    }

    @Test
    fun `the no-confirmation request is for Android 12 and newer only`() {
        assertFalse(rules.requireNoUserAction(26))
        assertFalse(rules.requireNoUserAction(30))
        assertTrue(rules.requireNoUserAction(31))
        assertTrue(rules.requireNoUserAction(36))
    }

    @Test
    fun `pending user action and success are not failures`() {
        assertEquals(StatusVerdict.NeedsConfirmation, rules.describeStatus(-1, null))
        assertEquals(StatusVerdict.Success, rules.describeStatus(0, "ignored"))
    }

    @Test
    fun `every failure status has a plain sentence`() {
        for (status in 1..8) {
            val v = rules.describeStatus(status, null)
            assertTrue("status $status", v is StatusVerdict.Failed)
            assertTrue((v as StatusVerdict.Failed).reason.isNotBlank())
        }
        assertTrue((rules.describeStatus(6, null) as StatusVerdict.Failed).reason.contains("storage"))
        assertTrue((rules.describeStatus(3, null) as StatusVerdict.Failed).reason.contains("cancelled"))
        assertTrue((rules.describeStatus(99, null) as StatusVerdict.Failed).reason.contains("99"))
    }

    @Test
    fun `Android's own message is quoted so the real reason is never lost`() {
        val v = rules.describeStatus(4, "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: Failed parse during installPackageLI")
        v as StatusVerdict.Failed
        assertTrue(v.reason.contains("INSTALL_PARSE_FAILED_MANIFEST_MALFORMED"))
        assertTrue(v.reason.contains("Android said:"))
        // a blank message adds nothing
        assertFalse((rules.describeStatus(1, "   ") as StatusVerdict.Failed).reason.contains("Android said"))
    }
}
