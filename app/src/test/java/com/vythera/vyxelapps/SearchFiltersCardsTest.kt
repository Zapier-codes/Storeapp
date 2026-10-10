package com.vythera.vyxelapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-P1 (content rating / parental filter) and S-P2 (age / device filter). These two client cards
 * shipped written-but-never-compiled; this file pins the rules so a future edit cannot quietly change
 * them, and it is the first automated exercise of [applySearchView]'s two extra terms.
 */
class SearchFiltersCardsTest {

    private fun repo(
        name          : String = "app",
        id            : Long   = 1L,
        contentRating : String? = null,
        minSdk        : Int    = 0,
    ) = GitHubRepo(
        id            = id,
        name          = name,
        full_name     = "someone/$name",
        stargazers_count = 0,
        updated_at    = "2026-01-01T00:00:00Z",
        contentRating = contentRating,
        minSdk        = minSdk,
    )

    // ── S-P1: contentClassOf ─────────────────────────────────────────────────

    @Test
    fun `contentClassOf places the common source strings`() {
        assertEquals(ContentClass.EVERYONE, contentClassOf("Everyone"))
        assertEquals(ContentClass.EVERYONE, contentClassOf("Everyone 10+"))
        assertEquals(ContentClass.EVERYONE, contentClassOf("All ages"))
        assertEquals(ContentClass.TEEN, contentClassOf("Teen"))
        assertEquals(ContentClass.TEEN, contentClassOf("Rated for 12+"))
        assertEquals(ContentClass.MATURE, contentClassOf("Mature 17+"))
        assertEquals(ContentClass.MATURE, contentClassOf("ESRB M"))
        assertEquals(ContentClass.ADULTS, contentClassOf("Adults only 18+"))
    }

    @Test
    fun `contentClassOf is case-insensitive and trims`() {
        assertEquals(ContentClass.TEEN, contentClassOf("  TEEN  "))
        assertEquals(ContentClass.MATURE, contentClassOf("mAtUrE"))
    }

    @Test
    fun `contentClassOf returns null for a blank or unplaceable rating`() {
        assertNull(contentClassOf(null))
        assertNull(contentClassOf(""))
        assertNull(contentClassOf("   "))
        assertNull(contentClassOf("Unrated"))
        assertNull(contentClassOf("PEGI 7")) // not one of the mappable tiers here
    }

    // ── S-P1: the ceiling filter ─────────────────────────────────────────────

    @Test
    fun `a content ceiling keeps everything at or below it`() {
        val apps = listOf(
            repo("e", id = 1, contentRating = "Everyone"),
            repo("t", id = 2, contentRating = "Teen"),
            repo("m", id = 3, contentRating = "Mature 17+"),
            repo("a", id = 4, contentRating = "Adults only 18+"),
        )
        val kept = applySearchView(apps, SearchSort.RELEVANCE, SearchFilters(contentClass = ContentClass.TEEN), installed = emptySet())

        assertEquals(listOf(1L, 2L), kept.map { it.id })
    }

    @Test
    fun `an unrated app is dropped by any content ceiling, never guessed in`() {
        val apps = listOf(repo("no-rating", id = 1, contentRating = null), repo("unplaceable", id = 2, contentRating = "PEGI 7"))

        val anyCeiling = applySearchView(apps, SearchSort.RELEVANCE, SearchFilters(contentClass = ContentClass.ADULTS), installed = emptySet())
        assertTrue(anyCeiling.isEmpty())
    }

    @Test
    fun `with no content ceiling every app stays, rated or not`() {
        val apps = listOf(repo("no-rating", id = 1), repo("mature", id = 2, contentRating = "Mature 17+"))

        val kept = applySearchView(apps, SearchSort.RELEVANCE, SearchFilters(contentClass = null), installed = emptySet())
        assertEquals(listOf(1L, 2L), kept.map { it.id })
    }

    // ── S-P2: the works-on-device filter ─────────────────────────────────────

    @Test
    fun `worksOnDevice drops only an app whose published minSdk the device provably fails`() {
        val apps = listOf(
            repo("fits", id = 1, minSdk = 26),
            repo("too-new", id = 2, minSdk = 35),
        )
        val kept = applySearchView(
            apps, SearchSort.RELEVANCE,
            SearchFilters(worksOnDevice = true), installed = emptySet(), deviceApiLevel = 30,
        )

        assertEquals(listOf(1L), kept.map { it.id })
    }

    @Test
    fun `an app that published no minSdk is never dropped`() {
        val apps = listOf(repo("unknown", id = 1, minSdk = 0))
        val kept = applySearchView(
            apps, SearchSort.RELEVANCE,
            SearchFilters(worksOnDevice = true), installed = emptySet(), deviceApiLevel = 21,
        )

        assertEquals(listOf(1L), kept.map { it.id })
    }

    @Test
    fun `an unknown device API level drops nothing`() {
        val apps = listOf(repo("too-new", id = 1, minSdk = 99))
        val kept = applySearchView(
            apps, SearchSort.RELEVANCE,
            SearchFilters(worksOnDevice = true), installed = emptySet(), deviceApiLevel = 0,
        )

        assertEquals(listOf(1L), kept.map { it.id })
    }

    @Test
    fun `the device filter is off unless the caller asks for it`() {
        val apps = listOf(repo("too-new", id = 1, minSdk = 99))
        val kept = applySearchView(apps, SearchSort.RELEVANCE, SearchFilters(), installed = emptySet(), deviceApiLevel = 21)

        assertEquals(listOf(1L), kept.map { it.id })
    }
}
