package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.DSTORE_ID_OFFSET
import com.vythera.vyxelapps.api.StoreUpdate
import com.vythera.vyxelapps.api.ZEALOT_ID_OFFSET
import com.vythera.vyxelapps.api.isDStoreRepoId
import com.vythera.vyxelapps.api.isStoreRepoId
import com.vythera.vyxelapps.api.isZealotRepoId
import com.vythera.vyxelapps.api.toRelease
import com.vythera.vyxelapps.api.toRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf h.iv. Written, NOT run (standing operator instruction: no testing, no build). Covers the pure
 * routing (which ids are store ids) and the two conversions the install path depends on. The network
 * routing itself (`StoreUpdateChecker.check`) is not exercised here — it needs a device/network.
 */
class StoreUpdateCheckTest {

    @Test
    fun store_ids_cover_both_buckets_and_nothing_else() {
        assertTrue(isStoreRepoId(ZEALOT_ID_OFFSET))
        assertTrue(isStoreRepoId(DSTORE_ID_OFFSET))
        assertTrue(isStoreRepoId(ZEALOT_ID_OFFSET + 42L))
        assertTrue(isStoreRepoId(DSTORE_ID_OFFSET + 7L))

        // A GitHub/GitLab/... synthetic id is not a store id.
        assertFalse(isStoreRepoId(0L))
        assertFalse(isStoreRepoId(9_000_000_000L))
        assertFalse(isStoreRepoId(1L))

        // The buckets do not overlap: a D-Store id is not a Zealot id, and vice versa.
        assertTrue(isZealotRepoId(ZEALOT_ID_OFFSET))
        assertFalse(isZealotRepoId(DSTORE_ID_OFFSET))
        assertTrue(isDStoreRepoId(DSTORE_ID_OFFSET))
        assertFalse(isDStoreRepoId(ZEALOT_ID_OFFSET))
    }

    private fun zealotUpdate() = StoreUpdate(
        repoId                    = ZEALOT_ID_OFFSET + 2L,
        repoName                  = "Appstore",
        currentTag                = "1.1.13",
        latestTag                 = "1.1.14",
        changelog                 = "",
        apkUrl                    = "https://zealot.example/download/releases/8",
        claimedSha256             = "a".repeat(64),
        claimedSigningFingerprint = "AA:BB:CC",
        installable               = true
    )

    @Test
    fun an_installable_update_carries_its_verified_claims_into_the_repo() {
        val repo = zealotUpdate().toRepo()
        assertEquals(AppSource.ZEALOT, repo.source)          // downloadAndInstall allows only this / non-DSTORE
        assertEquals("https://zealot.example/download/releases/8", repo.apkUrl)
        assertEquals("1.1.14", repo.cdnVersion)
        assertEquals("a".repeat(64), repo.claimedSha256)
        assertEquals("AA:BB:CC", repo.claimedSigningFingerprint)
    }

    @Test
    fun an_installable_update_makes_one_apk_asset_for_the_downloader() {
        val release = zealotUpdate().toRelease()
        assertEquals("1.1.14", release.tag_name)
        assertEquals(1, release.assets.size)
        assertEquals("https://zealot.example/download/releases/8", release.assets.first().browser_download_url)
        assertTrue(release.assets.first().name.endsWith(".apk"))
    }

    @Test
    fun a_dstore_only_update_carries_no_verified_claim() {
        val update = StoreUpdate(
            repoId                    = DSTORE_ID_OFFSET + 5L,
            repoName                  = "chess-game",
            currentTag                = "2.0",
            latestTag                 = "2.1",
            changelog                 = "",
            apkUrl                    = "",
            claimedSha256             = null,
            claimedSigningFingerprint = null,
            installable               = false
        )
        // updateAll() refuses to install this: it is shown, never installed.
        assertFalse(update.installable)
        assertTrue(update.apkUrl.isBlank())
        assertNull(update.claimedSha256)
        assertNull(update.claimedSigningFingerprint)
    }
}
