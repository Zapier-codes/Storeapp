package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.ZealotEntry
import com.vythera.vyxelapps.api.ZealotIcon
import com.vythera.vyxelapps.api.ZealotListing
import com.vythera.vyxelapps.api.ZealotVersion
import com.vythera.vyxelapps.expressive.data.model.SourceId
import com.vythera.vyxelapps.expressive.data.source.toAppItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf j.vii.b: the pure Zealot-entry -> AppItem mapping, and the one property the whole
 * leaf rests on — that the checksum and signing-fingerprint claims reach the card, so
 * j.vii.a's verify step has something to check.
 */
class ZealotSourceTest {

    private fun entry(
        id: String = "42",
        pkg: String? = "com.example.app",
        slug: String = "example",
        versions: List<ZealotVersion> = listOf(
            ZealotVersion(
                version_name = "1.2.0",
                version_code = "12",
                status = "available",
                download_url = "https://cdn.example/app.apk",
                sha256 = "a".repeat(64),
                size_bytes = 1234,
                signing_fingerprint = "AA:BB:CC",
                changelog = "Fixes",
            )
        ),
    ) = ZealotEntry(
        id = id,
        package_name = pkg,
        slug = slug,
        listing = ZealotListing(title = "Example", icon = ZealotIcon(url = "https://cdn.example/icon.png")),
        summary = "An example",
        versions = versions,
    )

    @Test
    fun `the checksum and signer claims reach the card`() {
        val item = entry().toAppItem()!!
        assertEquals(SourceId.Zealot, item.source)
        assertEquals("a".repeat(64), item.claimedSha256)
        assertEquals("AA:BB:CC", item.claimedSigningFingerprint)
        assertEquals("https://cdn.example/app.apk", item.downloadUrl)
        assertEquals(1234L, item.sizeBytes)
        assertEquals("12", item.versionCode.toString())
        assertEquals("Example", item.name)
        assertEquals("com.example.app", item.packageName)
    }

    @Test
    fun `a withdrawn newest version is skipped in favour of a live older one`() {
        val item = entry(
            versions = listOf(
                ZealotVersion(version_name = "2.0.0", version_code = "20", status = "pulled", download_url = "https://cdn.example/2.apk", sha256 = "b".repeat(64)),
                ZealotVersion(version_name = "1.9.0", version_code = "19", status = "available", download_url = "https://cdn.example/19.apk", sha256 = "c".repeat(64)),
            )
        ).toAppItem()!!
        assertEquals("1.9.0", item.version)
        assertEquals("https://cdn.example/19.apk", item.downloadUrl)
        assertEquals("c".repeat(64), item.claimedSha256)
    }

    @Test
    fun `a non-https download url is dropped rather than offered`() {
        val item = entry(
            versions = listOf(ZealotVersion(version_name = "1.0", status = "available", download_url = "http://insecure.example/app.apk"))
        ).toAppItem()!!
        assertNull(item.downloadUrl)
    }

    @Test
    fun `an entry with neither package nor slug is not a card`() {
        // No package name and an empty slug/id leaves nothing to dedupe or install by.
        val bare = ZealotEntry(id = "", package_name = null, slug = "")
        assertNull(bare.toAppItem())
    }

    @Test
    fun `the id lands in Zealot's own bucket so both shells key the same app`() {
        val item = entry(id = "7").toAppItem()!!
        assertTrue(item.id.contains(":"))
        val numeric = item.id.substringAfter(':').toLong()
        assertTrue(numeric >= com.vythera.vyxelapps.api.ZEALOT_ID_OFFSET)
    }
}
