package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.DSTORE_ID_OFFSET
import com.vythera.vyxelapps.api.DStoreApp
import com.vythera.vyxelapps.api.ZEALOT_ID_OFFSET
import com.vythera.vyxelapps.api.dstoreRepoId
import com.vythera.vyxelapps.api.isDStoreRepoId
import com.vythera.vyxelapps.api.isZealotRepoId
import com.vythera.vyxelapps.api.toUnifiedRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf 7.b.iii.zi. Written, NOT run (standing operator instruction: no testing, no build). The
 * expected ids were computed with an independent implementation of FNV-1a 64 (Python) and masked to
 * 61 bits, not copied from this code's output.
 */
class DStoreEntryTest {

    private fun app(over: DStoreApp.() -> DStoreApp = { this }) = DStoreApp(
        slug = "chess-game",
        package_name = "com.example.chess",
        origin = "aptoide",
        name = "Chess",
        summary = "  A chess game  ",
        icon = "https://img.example/chess.png",
        version = "2.1",
        app_type = "game",
        category = "board",
        developer_name = "Acme Ltd",
        license = "Not provided",
        size_mb = 12.5,
        download_url = "https://dl.example/chess.apk",
        reported_downloads = 5000L,
        updated_at = "2026-09-12T12:00:00+00:00"
    ).over()

    @Test
    fun source_and_package_identity() {
        val repo = app().toUnifiedRepo()
        assertEquals(AppSource.DSTORE, repo.source)
        assertEquals("com.example.chess", repo.packageName)
        assertEquals("com.example.chess", repo.full_name)
        assertEquals("Chess", repo.name)
        assertEquals("A chess game", repo.description)
        assertEquals("2.1", repo.cdnVersion)
        assertEquals("Acme Ltd", repo.owner.login)
        assertEquals("https://img.example/chess.png", repo.owner.avatar_url)
        assertNull(repo.originTenantId)
    }

    @Test
    fun an_unsigned_entry_never_carries_a_verified_claim() {
        val repo = app().toUnifiedRepo()
        assertNull(repo.claimedSha256)
        assertNull(repo.claimedSigningFingerprint)
    }

    @Test
    fun apk_url_only_from_https() {
        assertEquals("https://dl.example/chess.apk", app().toUnifiedRepo().apkUrl)
        assertEquals("", app { copy(download_url = "http://dl.example/chess.apk") }.toUnifiedRepo().apkUrl)
        assertEquals("", app { copy(download_url = "javascript:alert(1)") }.toUnifiedRepo().apkUrl)
        assertEquals("", app { copy(download_url = "") }.toUnifiedRepo().apkUrl)
        assertEquals("", app { copy(download_url = null) }.toUnifiedRepo().apkUrl)
    }

    @Test
    fun icon_only_from_https() {
        assertEquals("", app { copy(icon = "http://img.example/i.png") }.toUnifiedRepo().owner.avatar_url)
        assertEquals("", app { copy(icon = null) }.toUnifiedRepo().owner.avatar_url)
    }

    @Test
    fun reported_downloads_is_not_turned_into_stars() {
        val repo = app().toUnifiedRepo()
        assertEquals(0, repo.stargazers_count)
        assertEquals(0, repo.forks_count)
    }

    @Test
    fun blank_fields_fall_back_without_throwing() {
        val repo = app { copy(name = "  ", package_name = " ", summary = "", developer_name = "") }.toUnifiedRepo()
        assertEquals("chess-game", repo.name)
        assertEquals("chess-game", repo.full_name)
        assertNull(repo.packageName)
        assertNull(repo.description)
        assertEquals("D-Store", repo.owner.login)
    }

    @Test
    fun ids_match_an_independent_fnv1a_computation() {
        assertEquals(1108972174487172236L, dstoreRepoId("a"))
        assertEquals(442602116444480684L, dstoreRepoId("chess-game"))
        assertEquals(764107152703581547L, dstoreRepoId("com.example.app"))
    }

    @Test
    fun ids_are_stable_distinct_and_in_their_own_bucket() {
        assertEquals(dstoreRepoId("chess-game"), dstoreRepoId("chess-game"))
        assertNotEquals(dstoreRepoId("chess-game"), dstoreRepoId("chess-game-2"))
        for (slug in listOf("a", "chess-game", "com.example.app", "x".repeat(200), "开发者")) {
            val id = dstoreRepoId(slug)
            assertTrue(id >= DSTORE_ID_OFFSET)
            assertTrue(isDStoreRepoId(id))
            assertFalse(isZealotRepoId(id))
        }
    }

    @Test
    fun zealot_bucket_stops_below_dstore() {
        assertTrue(isZealotRepoId(ZEALOT_ID_OFFSET))
        assertTrue(isZealotRepoId(ZEALOT_ID_OFFSET + 2_147_483_647L))
        assertFalse(isZealotRepoId(ZEALOT_ID_OFFSET - 1))
        assertFalse(isZealotRepoId(DSTORE_ID_OFFSET))
        assertFalse(isDStoreRepoId(ZEALOT_ID_OFFSET + 2_147_483_647L))
    }
}
