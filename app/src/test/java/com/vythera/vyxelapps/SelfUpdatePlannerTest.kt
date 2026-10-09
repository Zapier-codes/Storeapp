package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.SelfUpdateDecision
import com.vythera.vyxelapps.api.SelfUpdatePlanner
import com.vythera.vyxelapps.api.ZealotCompatibility
import com.vythera.vyxelapps.api.ZealotEntry
import com.vythera.vyxelapps.api.ZealotIndex
import com.vythera.vyxelapps.api.ZealotVersion
import com.vythera.vyxelapps.api.offerOrNull
import com.vythera.vyxelapps.api.parseZealotEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf h.i.zi. Written, NOT run (standing operator instruction: no testing, no build).
 */
class SelfUpdatePlannerTest {

    private val pkg = "com.vythera.vyxelapps"
    private val hash = "A".repeat(32) + "b".repeat(32)

    private fun version(
        code: String? = "220",
        name: String? = "1.1.9",
        status: String? = "available",
        url: String? = "https://zealot.example/download/releases/9",
        sha: String? = hash,
        size: Long? = 31_238_330L,
        fingerprint: String? = "32f5e77c79f208be49d25108867a8860a342b28b",
        minSdk: Int? = 26,
        changelog: String? = "  Faster search\nmore  "
    ) = ZealotVersion(
        version_name = name,
        version_code = code,
        status = status,
        download_url = url,
        sha256 = sha,
        size_bytes = size,
        signing_fingerprint = fingerprint,
        changelog = changelog,
        compatibility = ZealotCompatibility(min_sdk = minSdk)
    )

    private fun entry(vararg versions: ZealotVersion, packageName: String? = pkg) =
        ZealotEntry(id = "2", package_name = packageName, slug = "appstore", versions = versions.toList())

    private fun plan(index: ZealotIndex, installedCode: Long = 218L, sdk: Int = 34) =
        SelfUpdatePlanner.plan(index, pkg, installedCode, sdk)

    private fun reason(decision: SelfUpdateDecision): String =
        (decision as SelfUpdateDecision.NoOffer).reason

    @Test
    fun `offers a newer available version with every field checked and normalised`() {
        val offer = plan(ZealotIndex(listOf(entry(version())))).offerOrNull()
        assertNotNull(offer)
        offer!!
        assertEquals("1.1.9", offer.versionName)
        assertEquals(220L, offer.versionCode)
        assertEquals("https://zealot.example/download/releases/9", offer.downloadUrl)
        assertEquals(hash.lowercase(), offer.sha256) // stored lower-case
        assertEquals(31_238_330L, offer.sizeBytes)
        assertEquals("32f5e77c79f208be49d25108867a8860a342b28b", offer.signingFingerprint)
        assertEquals(26, offer.minSdk)
        assertEquals("Faster search\nmore", offer.changelog) // trimmed, first line is the banner's
    }

    @Test
    fun `highest version_code wins whatever the order, compared as numbers`() {
        val index = ZealotIndex(
            listOf(entry(version(code = "99", name = "a"), version(code = "1000", name = "c"), version(code = "230", name = "b")))
        )
        val offer = plan(index, installedCode = 50L).offerOrNull()
        assertEquals(1000L, offer!!.versionCode) // "1000" beats "230" and "99" (not a string compare)
        assertEquals("c", offer.versionName)
    }

    @Test
    fun `nothing is offered at or below the installed version`() {
        val index = ZealotIndex(listOf(entry(version(code = "218"), version(code = "100"))))
        val decision = plan(index, installedCode = 218L)
        assertNull(decision.offerOrNull())
        assertTrue(reason(decision).contains("no available version is newer than 218"))
    }

    @Test
    fun `halted and pulled versions are never offered`() {
        val index = ZealotIndex(listOf(entry(version(code = "300", status = "halted"), version(code = "299", status = "pulled"))))
        val decision = plan(index)
        assertNull(decision.offerOrNull())
        assertTrue(reason(decision).contains("no available version"))
    }

    @Test
    fun `a newer complete version is offered over an incomplete higher one`() {
        val index = ZealotIndex(listOf(entry(version(code = "300", sha = null), version(code = "220"))))
        assertEquals(220L, plan(index).offerOrNull()!!.versionCode)
    }

    @Test
    fun `a missing or malformed field is refused with a reason, never defaulted`() {
        fun refusedFor(v: ZealotVersion): String {
            val decision = plan(ZealotIndex(listOf(entry(v))))
            assertNull(decision.offerOrNull())
            return reason(decision)
        }
        assertTrue(refusedFor(version(sha = null)).contains("no sha256"))
        assertTrue(refusedFor(version(sha = "  ")).contains("no sha256"))
        assertTrue(refusedFor(version(sha = "abc123")).contains("64 hex digits"))
        assertTrue(refusedFor(version(url = null)).contains("no download_url"))
        assertTrue(refusedFor(version(url = "/download/releases/9")).contains("https"))
        assertTrue(refusedFor(version(url = "http://zealot.example/x")).contains("https"))
        assertTrue(refusedFor(version(url = "https://")).contains("https"))
        assertTrue(refusedFor(version(size = null)).contains("no size_bytes"))
        assertTrue(refusedFor(version(size = 0L)).contains("size_bytes"))
        assertTrue(refusedFor(version(fingerprint = null)).contains("no signing_fingerprint"))
        assertTrue(refusedFor(version(fingerprint = "")).contains("no signing_fingerprint"))
        assertTrue(refusedFor(version(status = null)).contains("no status"))
        assertTrue(refusedFor(version(name = " ")).contains("no version_name"))
    }

    @Test
    fun `min_sdk above the device is refused and a missing min_sdk is accepted`() {
        val tooNew = plan(ZealotIndex(listOf(entry(version(minSdk = 35)))), sdk = 34)
        assertNull(tooNew.offerOrNull())
        assertTrue(reason(tooNew).contains("API 35"))

        assertNotNull(plan(ZealotIndex(listOf(entry(version(minSdk = 34)))), sdk = 34).offerOrNull()) // equal is fine
        assertNull(plan(ZealotIndex(listOf(entry(version(minSdk = null)))), sdk = 34).offerOrNull()?.minSdk)
        assertNotNull(plan(ZealotIndex(listOf(entry(version(minSdk = null)))), sdk = 34).offerOrNull())
    }

    @Test
    fun `a version code that is not a whole number is skipped`() {
        val unreadable = plan(ZealotIndex(listOf(entry(version(code = "v2"), version(code = null), version(code = "0")))))
        assertNull(unreadable.offerOrNull())
        assertTrue(reason(unreadable).contains("version_code"))
        // ...and does not hide a readable newer one next to it.
        assertEquals(221L, plan(ZealotIndex(listOf(entry(version(code = "v2"), version(code = "221"))))).offerOrNull()!!.versionCode)
    }

    @Test
    fun `no entry, a blank package or two entries for the package offers nothing`() {
        assertNull(plan(ZealotIndex(listOf(entry(version(), packageName = "com.other.app")))).offerOrNull())
        assertNull(plan(ZealotIndex(emptyList())).offerOrNull())
        assertNull(SelfUpdatePlanner.plan(ZealotIndex(listOf(entry(version()))), "  ", 1L, 34).offerOrNull())
        val twice = plan(ZealotIndex(listOf(entry(version()), entry(version(code = "900")))))
        assertNull(twice.offerOrNull())
        assertTrue(reason(twice).contains("2 entries"))
        assertNull(plan(ZealotIndex(listOf(entry(version(), packageName = null)))).offerOrNull()) // an entry with no package never matches
    }

    @Test
    fun `reads version_code and status off a real index shape`() {
        val json = """
            {"apps":[{"id":"2","package_name":"com.vythera.vyxelapps","slug":"appstore","versions":[
              {"version_name":"1.1.12","version_code":"230","download_url":"https://zealot.example/download/releases/7",
               "sha256":"${hash}","size_bytes":31238330,"signing_fingerprint":"32f5e77c","status":"available",
               "changelog":"Fixes","compatibility":{"min_sdk":26}},
              {"version_name":"1.1.4","version_code":"218","status":"available"}]}]}
        """.trimIndent()
        val entries = parseZealotEntries(json)
        val offer = plan(ZealotIndex(entries)).offerOrNull()
        assertEquals(230L, offer!!.versionCode)
        assertEquals("Fixes", offer.changelog)
    }
}
