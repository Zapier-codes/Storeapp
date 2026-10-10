package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.Verifier
import com.vythera.vyxelapps.api.VerifierPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf j.vii.a: the one install policy both shells use. Written, NOT run (no Android SDK in the session that wrote it).
 */
class VerifierPolicyTest {

    @Test
    fun `a file that matched every claim may be installed`() {
        assertNull(VerifierPolicy.blockedReason(Verifier.Result.Trusted(setOf(Verifier.Check.CHECKSUM, Verifier.Check.SIGNING_FINGERPRINT))))
    }

    @Test
    fun `a missing claim never blocks, so sources that publish none keep working`() {
        assertNull(VerifierPolicy.blockedReason(Verifier.Result.NothingToVerify(setOf(Verifier.Check.CHECKSUM, Verifier.Check.SIGNING_FINGERPRINT))))
        assertNull(
            VerifierPolicy.blockedReason(
                Verifier.Result.PartiallyVerified(setOf(Verifier.Check.CHECKSUM), setOf(Verifier.Check.SIGNING_FINGERPRINT))
            )
        )
    }

    @Test
    fun `a checksum mismatch blocks and says so`() {
        val reason = VerifierPolicy.blockedReason(Verifier.Result.ChecksumMismatch(expected = "aa", actual = "bb"))
        assertNotNull(reason)
        assertTrue(reason!!.contains("checksum"))
        assertTrue(reason.contains("refusing to install"))
    }

    @Test
    fun `a signing certificate mismatch blocks and says so`() {
        val reason = VerifierPolicy.blockedReason(Verifier.Result.SignatureMismatch(expected = "aa", actual = listOf("bb")))
        assertNotNull(reason)
        assertTrue(reason!!.contains("signing certificate"))
    }

    @Test
    fun `a file that cannot be read or parsed blocks, because nothing was compared`() {
        val unreadable = VerifierPolicy.blockedReason(Verifier.Result.Unreadable("EACCES"))
        val unparsable = VerifierPolicy.blockedReason(Verifier.Result.Unparsable("no signing info"))
        assertTrue(unreadable!!.contains("EACCES"))
        assertTrue(unparsable!!.contains("no signing info"))
    }
}
