package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.DStoreCatalogClient
import com.vythera.vyxelapps.api.DStoreOrder
import com.vythera.vyxelapps.api.DStoreType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf 7.b.iii.zo. Written, NOT run (standing operator instruction: no testing, no build). Covers
 * the two pure functions, [DStoreCatalogClient.buildCatalogUrl] and [DStoreCatalogClient.parseCatalogPage];
 * the network call itself is not tested here.
 */
class DStoreCatalogClientTest {

    private val base = "https://store.example"

    private fun url(
        baseUrl: String = base,
        order: DStoreOrder = DStoreOrder.TOP,
        type: DStoreType? = null,
        category: String? = null,
        query: String? = null,
        cursor: String? = null,
        limit: Int = 50
    ) = DStoreCatalogClient.buildCatalogUrl(baseUrl, order, type, category, query, cursor, limit)

    private val goodApp = """
        {"origin":"aptoide","package_name":"com.example.chess","slug":"chess","name":"Chess","version":"1.2",
         "icon":"https://img.example/c.png","summary":"A game","app_type":"game","category":"board",
         "developer":{"slug":"acme","name":"Acme"},"license":"Free","size_mb":12.5,
         "download_url":"https://dl.example/c.apk","reported_downloads":4200,"updated_at":"2026-10-01T00:00:00Z"}
    """.trimIndent()

    private fun envelope(apps: String, cursor: String = "null") = """{"apps":[$apps],"next_cursor":$cursor}"""

    // ── buildCatalogUrl ────────────────────────────────────────────────────────

    @Test fun blankOrNonHttpsBaseIsNotConfigured() {
        assertNull(url(baseUrl = ""))
        assertNull(url(baseUrl = "   "))
        assertNull(url(baseUrl = "http://store.example"))
        assertNull(url(baseUrl = "not a url"))
    }

    @Test fun defaultUrlCarriesOrderAndLimit() {
        assertEquals("https://store.example/api/catalog?order=top&limit=50", url())
    }

    @Test fun trailingSlashesOnTheBaseAreIgnored() {
        assertEquals("https://store.example/api/catalog?order=new&limit=10", url(baseUrl = "https://store.example//", order = DStoreOrder.NEW, limit = 10))
    }

    @Test fun limitIsClamped() {
        assertEquals("https://store.example/api/catalog?order=top&limit=100", url(limit = 5000))
        assertEquals("https://store.example/api/catalog?order=top&limit=1", url(limit = 0))
    }

    @Test fun typeCategoryAndCursorAreSent() {
        assertEquals(
            "https://store.example/api/catalog?order=new&limit=20&type=game&category=board&cursor=abc_-9",
            url(order = DStoreOrder.NEW, type = DStoreType.GAME, category = "board", cursor = "abc_-9", limit = 20)
        )
    }

    @Test fun categoryNeedsATypeAndMayNotBeBlank() {
        assertNull(url(category = "board"))
        assertNull(url(type = DStoreType.APP, category = ""))
    }

    @Test fun searchIsTopOrderOnlyWithNoCategory() {
        assertEquals("https://store.example/api/catalog?order=top&limit=50&q=chess", url(query = "  chess "))
        assertNull(url(order = DStoreOrder.NEW, query = "chess"))
        assertNull(url(type = DStoreType.APP, category = "board", query = "chess"))
    }

    @Test fun searchNeedleIsEncodedAndCutAtOneHundredCodePoints() {
        assertTrue(url(query = "a b&c")!!.endsWith("&q=a%20b%26c"))
        val long = "x".repeat(150)
        assertEquals(100, DStoreCatalogClient.cutSearchNeedle(long).length)
        // 120 emoji are 240 UTF-16 units but 120 code points; the cut is by code point.
        val emoji = "\uD83D\uDE00".repeat(120)
        val cut = DStoreCatalogClient.cutSearchNeedle(emoji)
        assertEquals(100, cut.codePointCount(0, cut.length))
    }

    @Test fun malformedCursorIsRefusedBeforeAnyRequest() {
        assertNull(url(cursor = "has space"))
        assertNull(url(cursor = "a/b"))
        assertNull(url(cursor = "a".repeat(601)))
        assertNotNull(url(cursor = "a".repeat(600)))
    }

    // ── parseCatalogPage ───────────────────────────────────────────────────────

    @Test fun parsesAFullApp() {
        val page = DStoreCatalogClient.parseCatalogPage(envelope(goodApp, "\"next-1_A\""))!!
        assertEquals("next-1_A", page.nextCursor)
        assertEquals(0, page.skipped)
        assertEquals(1, page.apps.size)
        val a = page.apps[0]
        assertEquals("chess", a.slug)
        assertEquals("com.example.chess", a.package_name)
        assertEquals("aptoide", a.origin)
        assertEquals("Acme", a.developer_name)
        assertEquals(12.5, a.size_mb, 0.0)
        assertEquals("https://dl.example/c.apk", a.download_url)
        assertEquals(4200L, a.reported_downloads)
        assertEquals("2026-10-01T00:00:00Z", a.updated_at)
    }

    @Test fun nullableFieldsStayNull() {
        val app = """{"slug":"x","icon":null,"download_url":null,"reported_downloads":null}"""
        val a = DStoreCatalogClient.parseCatalogPage(envelope(app))!!.apps[0]
        assertNull(a.icon)
        assertNull(a.download_url)
        assertNull(a.reported_downloads)
    }

    @Test fun lastPageHasNoCursorAndEmptyPageIsValid() {
        val page = DStoreCatalogClient.parseCatalogPage(envelope(""))!!
        assertEquals(0, page.apps.size)
        assertNull(page.nextCursor)
    }

    @Test fun envelopeProblemsFailTheWholePage() {
        assertNull(DStoreCatalogClient.parseCatalogPage(""))
        assertNull(DStoreCatalogClient.parseCatalogPage("[]"))
        assertNull(DStoreCatalogClient.parseCatalogPage("\"text\""))
        assertNull(DStoreCatalogClient.parseCatalogPage("""{"next_cursor":null}"""))
        assertNull(DStoreCatalogClient.parseCatalogPage("""{"apps":{},"next_cursor":null}"""))
        assertNull(DStoreCatalogClient.parseCatalogPage("""{"error":"The catalog is not available"}"""))
        assertNull(DStoreCatalogClient.parseCatalogPage(envelope(goodApp, "5")))
        assertNull(DStoreCatalogClient.parseCatalogPage(envelope(goodApp, "\"\"")))
        assertNull(DStoreCatalogClient.parseCatalogPage(envelope(goodApp, "\"has space\"")))
    }

    @Test fun morePagesThanTheLimitFailsThePage() {
        val many = (1..101).joinToString(",") { """{"slug":"s$it"}""" }
        assertNull(DStoreCatalogClient.parseCatalogPage(envelope(many)))
        val exactly = (1..100).joinToString(",") { """{"slug":"s$it"}""" }
        assertEquals(100, DStoreCatalogClient.parseCatalogPage(envelope(exactly))!!.apps.size)
    }

    @Test fun malformedAppsAreSkippedAndCounted() {
        val apps = listOf(
            goodApp,
            "42",                                  // not an object
            """{"name":"no slug"}""",              // missing slug
            """{"slug":"  "}""",                   // blank slug
            """{"slug":"a","name":7}""",           // wrong type
            """{"slug":"b","size_mb":"big"}""",    // wrong type
            """{"slug":"c","size_mb":-1}""",       // negative size
            """{"slug":"d","reported_downloads":1.5}""", // not a whole number
            """{"slug":"e","developer":"Acme"}""", // developer must be an object
            """{"slug":"fine"}"""                  // bare but valid
        ).joinToString(",")
        val page = DStoreCatalogClient.parseCatalogPage(envelope(apps))!!
        assertEquals(listOf("chess", "fine"), page.apps.map { it.slug })
        assertEquals(8, page.skipped)
    }

    @Test fun aRepeatedSlugIsSkipped() {
        val apps = """{"slug":"dup","name":"First"},{"slug":"dup","name":"Second"}"""
        val page = DStoreCatalogClient.parseCatalogPage(envelope(apps))!!
        assertEquals(1, page.apps.size)
        assertEquals("First", page.apps[0].name)
        assertEquals(1, page.skipped)
    }
}
