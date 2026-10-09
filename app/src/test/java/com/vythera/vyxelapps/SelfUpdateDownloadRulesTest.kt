package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.ContentRange
import com.vythera.vyxelapps.api.FinalVerdict
import com.vythera.vyxelapps.api.ResponsePlan
import com.vythera.vyxelapps.api.ResumePlan
import com.vythera.vyxelapps.api.SelfUpdateDownloadRules
import com.vythera.vyxelapps.api.SelfUpdateDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf h.ii.zi. Written, NOT run (standing operator instruction: no testing, no build). The expected values
 * come from the rules in `SelfUpdateDownload.kt` and from HTTP's Range semantics, not from running the code.
 */
class SelfUpdateDownloadRulesTest {

    private val size = 31_238_330L
    private val rules = SelfUpdateDownloadRules

    @Test
    fun `resume plan follows the part-file length`() {
        assertEquals(ResumePlan.StartFresh, rules.planResume(0L, size))
        assertEquals(ResumePlan.StartFresh, rules.planResume(-5L, size))
        assertEquals(ResumePlan.ResumeAt(1_000L), rules.planResume(1_000L, size))
        assertEquals(ResumePlan.AlreadyComplete, rules.planResume(size, size))
        assertEquals(ResumePlan.DiscardAndRestart, rules.planResume(size + 1L, size))
    }

    @Test
    fun `a full 200 answer is written from zero and its length must match`() {
        assertEquals(ResponsePlan.Write(0L), rules.planResponse(200, 0L, null, size, size))
        assertEquals(ResponsePlan.Write(0L), rules.planResponse(200, 0L, null, -1L, size)) // length unknown is allowed
        assertTrue(rules.planResponse(200, 0L, null, size - 1L, size) is ResponsePlan.Fail)
        assertTrue(rules.planResponse(200, 0L, null, size + 1L, size) is ResponsePlan.Fail)
    }

    @Test
    fun `a 200 answer to a Range request means the server ignored it, so the file restarts from zero`() {
        assertEquals(ResponsePlan.Write(0L), rules.planResponse(200, 5_000L, null, size, size))
    }

    @Test
    fun `a 206 answer is appended only when it starts where it was asked to`() {
        val header = "bytes 5000-${size - 1}/$size"
        assertEquals(ResponsePlan.Write(5_000L), rules.planResponse(206, 5_000L, header, size - 5_000L, size))
        assertEquals(ResponsePlan.Write(5_000L), rules.planResponse(206, 5_000L, header, -1L, size))
        // wrong start, missing header, wrong total, wrong remaining length: all refused
        assertTrue(rules.planResponse(206, 5_000L, "bytes 4000-${size - 1}/$size", -1L, size) is ResponsePlan.Fail)
        assertTrue(rules.planResponse(206, 5_000L, null, -1L, size) is ResponsePlan.Fail)
        assertTrue(rules.planResponse(206, 5_000L, "bytes 5000-${size - 1}/${size + 10}", -1L, size) is ResponsePlan.Fail)
        assertTrue(rules.planResponse(206, 5_000L, header, size - 4_000L, size) is ResponsePlan.Fail)
    }

    @Test
    fun `a 206 answer with an unknown total is accepted when the rest is consistent`() {
        assertEquals(ResponsePlan.Write(5_000L), rules.planResponse(206, 5_000L, "bytes 5000-${size - 1}/*", size - 5_000L, size))
    }

    @Test
    fun `416 restarts a resumed download once and fails a fresh one, other codes fail`() {
        assertEquals(ResponsePlan.RestartFresh, rules.planResponse(416, 5_000L, null, -1L, size))
        assertTrue(rules.planResponse(416, 0L, null, -1L, size) is ResponsePlan.Fail)
        assertTrue(rules.planResponse(404, 0L, null, -1L, size) is ResponsePlan.Fail)
        assertTrue(rules.planResponse(500, 5_000L, null, -1L, size) is ResponsePlan.Fail)
        assertTrue((rules.planResponse(403, 0L, null, -1L, size) as ResponsePlan.Fail).reason.contains("403"))
    }

    @Test
    fun `content range parsing accepts the documented shapes and rejects the rest`() {
        assertEquals(ContentRange(0L, 99L, 100L), rules.parseContentRange("bytes 0-99/100"))
        assertEquals(ContentRange(5L, 9L, null), rules.parseContentRange("  BYTES 5-9/*  "))
        assertNull(rules.parseContentRange(null))
        assertNull(rules.parseContentRange(""))
        assertNull(rules.parseContentRange("bytes */100")) // the form a 416 uses, not a body range
        assertNull(rules.parseContentRange("bytes 9-5/100")) // reversed
        assertNull(rules.parseContentRange("items 0-9/10"))
        assertNull(rules.parseContentRange("bytes 0-9/ten"))
    }

    @Test
    fun `only an exact byte count is accepted`() {
        assertEquals(FinalVerdict.Accept, rules.judgeFinalSize(size, size))
        assertEquals(FinalVerdict.TooShort(1L), rules.judgeFinalSize(size - 1L, size))
        assertEquals(FinalVerdict.TooShort(size), rules.judgeFinalSize(0L, size))
        assertEquals(FinalVerdict.TooLong(1L), rules.judgeFinalSize(size + 1L, size))
    }

    @Test
    fun `retries are limited to three attempts in all`() {
        assertTrue(rules.mayRetry(0))
        assertTrue(rules.mayRetry(1))
        assertFalse(rules.mayRetry(2))
        assertFalse(rules.mayRetry(7))
    }

    @Test
    fun `free space must cover what is still to fetch plus the margin`() {
        val mb = 1024L * 1024L
        assertTrue(rules.enoughSpace(usableBytes = 100 * mb, expectedSize = 31 * mb, alreadyHaveBytes = 0L))
        assertFalse(rules.enoughSpace(usableBytes = 38 * mb, expectedSize = 31 * mb, alreadyHaveBytes = 0L)) // 31 + 8 > 38
        assertTrue(rules.enoughSpace(usableBytes = 20 * mb, expectedSize = 31 * mb, alreadyHaveBytes = 20 * mb)) // 11 + 8 <= 20
        assertTrue(rules.enoughSpace(usableBytes = 9 * mb, expectedSize = 31 * mb, alreadyHaveBytes = 40 * mb)) // nothing left to fetch
    }

    @Test
    fun `progress percent rounds down and never leaves 0 to 100`() {
        assertEquals(0, SelfUpdateDownloadState.Downloading(1L, 0L, 0L).percent)
        assertEquals(0, SelfUpdateDownloadState.Downloading(1L, 5L, 1000L).percent)
        assertEquals(50, SelfUpdateDownloadState.Downloading(1L, 500L, 1000L).percent)
        assertEquals(99, SelfUpdateDownloadState.Downloading(1L, 999L, 1000L).percent)
        assertEquals(100, SelfUpdateDownloadState.Downloading(1L, 2000L, 1000L).percent)
        assertEquals(0, SelfUpdateDownloadState.Downloading(1L, -4L, 1000L).percent)
    }
}
