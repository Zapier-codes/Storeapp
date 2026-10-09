package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.SelfUpdateBanner
import com.vythera.vyxelapps.api.SelfUpdateBannerState
import com.vythera.vyxelapps.api.SelfUpdateCheckPhase
import com.vythera.vyxelapps.api.SelfUpdateDownloadState
import com.vythera.vyxelapps.api.Verifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf h.ii.zo. Written, NOT run (standing operator instruction: no testing, no build). Expected values come
 * from the rules in `SelfUpdateBanner.kt`, not from running the code.
 */
class SelfUpdateBannerTest {

    private val name = "1.1.15"
    private val code = 229L
    private fun state(
        download: SelfUpdateDownloadState = SelfUpdateDownloadState.Idle,
        phase: SelfUpdateCheckPhase = SelfUpdateCheckPhase.None,
        changelog: String? = "Faster search\nmore"
    ) = SelfUpdateBanner.stateFor(name, code, changelog, download, phase)

    @Test
    fun `nothing in progress is Available with the first changelog line`() {
        assertEquals(SelfUpdateBannerState.Available(name, "Faster search"), state())
        assertEquals(SelfUpdateBannerState.Available(name, null), state(changelog = "  \n "))
        assertEquals(SelfUpdateBannerState.Available(name, null), state(changelog = null))
    }

    @Test
    fun `changelog line skips blank lines, trims, and cuts long text with an ellipsis`() {
        assertEquals("Fixes", SelfUpdateBanner.firstChangelogLine("\n\n  Fixes  \nnext"))
        val long = "x".repeat(80)
        val cut = SelfUpdateBanner.firstChangelogLine(long)!!
        assertEquals(60, cut.length)
        assertTrue(cut.endsWith("\u2026"))
        assertEquals("y".repeat(60), SelfUpdateBanner.firstChangelogLine("y".repeat(60)))
    }

    @Test
    fun `a running download of this offer shows its percent`() {
        val s = state(download = SelfUpdateDownloadState.Downloading(code, 500L, 1000L))
        assertEquals(SelfUpdateBannerState.Downloading(name, 50), s)
    }

    @Test
    fun `a failed download of this offer shows its reason`() {
        val s = state(download = SelfUpdateDownloadState.Failed(code, "Not enough free storage to download the update."))
        assertEquals(SelfUpdateBannerState.Failed(name, "Not enough free storage to download the update."), s)
    }

    @Test
    fun `a finished download with no check yet is Verifying`() {
        assertEquals(SelfUpdateBannerState.Verifying(name), state(download = SelfUpdateDownloadState.Downloaded(code, "/x.apk", 10L)))
    }

    @Test
    fun `the check phase wins over the download state`() {
        val done = SelfUpdateDownloadState.Downloaded(code, "/x.apk", 10L)
        assertEquals(SelfUpdateBannerState.Verifying(name), state(done, SelfUpdateCheckPhase.Verifying(code)))
        assertEquals(SelfUpdateBannerState.ReadyToInstall(name), state(done, SelfUpdateCheckPhase.Ready(code, "/x.apk")))
        assertEquals(SelfUpdateBannerState.Failed(name, "bad"), state(done, SelfUpdateCheckPhase.Failed(code, "bad")))
    }

    @Test
    fun `states and phases of another version are ignored`() {
        val other = code - 1L
        assertEquals(SelfUpdateBannerState.Available(name, "Faster search"),
            state(SelfUpdateDownloadState.Downloading(other, 900L, 1000L), SelfUpdateCheckPhase.Ready(other, "/old.apk")))
        assertEquals(SelfUpdateBannerState.Available(name, "Faster search"),
            state(SelfUpdateDownloadState.Failed(other, "x"), SelfUpdateCheckPhase.Failed(other, "y")))
        assertEquals(SelfUpdateBannerState.Available(name, "Faster search"),
            state(SelfUpdateDownloadState.Downloaded(other, "/old.apk", 5L)))
    }

    @Test
    fun `only a result that checked both the checksum and the signer is Ready`() {
        val both = Verifier.Result.Trusted(setOf(Verifier.Check.CHECKSUM, Verifier.Check.SIGNING_FINGERPRINT))
        assertEquals(SelfUpdateCheckPhase.Ready(code, "/x.apk"), SelfUpdateBanner.phaseFor(both, code, "/x.apk"))

        val onlySha = Verifier.Result.Trusted(setOf(Verifier.Check.CHECKSUM))
        assertTrue(SelfUpdateBanner.phaseFor(onlySha, code, "/x.apk") is SelfUpdateCheckPhase.Failed)
        val partial = Verifier.Result.PartiallyVerified(setOf(Verifier.Check.CHECKSUM), setOf(Verifier.Check.SIGNING_FINGERPRINT))
        assertTrue(SelfUpdateBanner.phaseFor(partial, code, "/x.apk") is SelfUpdateCheckPhase.Failed)
        val nothing = Verifier.Result.NothingToVerify(setOf(Verifier.Check.CHECKSUM, Verifier.Check.SIGNING_FINGERPRINT))
        assertTrue(SelfUpdateBanner.phaseFor(nothing, code, "/x.apk") is SelfUpdateCheckPhase.Failed)
    }

    @Test
    fun `every failing check becomes a plain Failed phase for this version`() {
        val results = listOf(
            Verifier.Result.ChecksumMismatch("aa", "bb"),
            Verifier.Result.SignatureMismatch("aa", listOf("bb")),
            Verifier.Result.Unreadable("disk"),
            Verifier.Result.Unparsable("zip")
        )
        for (r in results) {
            val phase = SelfUpdateBanner.phaseFor(r, code, "/x.apk")
            assertTrue(phase is SelfUpdateCheckPhase.Failed)
            assertEquals(code, (phase as SelfUpdateCheckPhase.Failed).versionCode)
            assertTrue(phase.reason.isNotBlank())
        }
        assertTrue((SelfUpdateBanner.phaseFor(results[0], code, "/x") as SelfUpdateCheckPhase.Failed).reason.contains("checksum"))
        assertTrue((SelfUpdateBanner.phaseFor(results[2], code, "/x") as SelfUpdateCheckPhase.Failed).reason.contains("disk"))
    }
}
