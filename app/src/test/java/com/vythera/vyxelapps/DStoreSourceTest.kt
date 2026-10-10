package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.DStoreApp
import com.vythera.vyxelapps.api.DSTORE_ID_OFFSET
import com.vythera.vyxelapps.expressive.data.model.SourceId
import com.vythera.vyxelapps.expressive.data.toAppItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf j.vii.c: the pure D-Store `DStoreApp` -> `AppItem` mapping, and the one property the
 * leaf rests on — that a D-Store card is **browse-only**, so `downloadUrl` is never set and
 * the install button can only read Unavailable (operator decision 5a/5b).
 */
class DStoreSourceTest {

    private fun app(
        slug: String = "example",
        pkg: String = "com.example.app",
        name: String = "Example",
        summary: String = "An example app",
        icon: String? = "https://cdn.example/icon.png",
        version: String = "1.2.0",
        downloadUrl: String? = "https://cdn.example/app.apk",
        developer: String = "Example Dev",
        category: String = "Tools",
        license: String = "Apache-2.0",
        sizeMb: Double = 2.5,
    ) = DStoreApp(
        slug = slug,
        package_name = pkg,
        name = name,
        summary = summary,
        icon = icon,
        version = version,
        category = category,
        developer_name = developer,
        license = license,
        size_mb = sizeMb,
        download_url = downloadUrl,
    )

    @Test
    fun `a D-Store card is never installable even when the row carries a download url`() {
        val item = app().toAppItem()
        assertEquals(SourceId.DStore, item.source)
        // The row HAS a download_url, and the mapping still refuses to expose it: D-Store's
        // catalog is unsigned, so there is nothing to verify a download against.
        assertNull(item.downloadUrl)
    }

    @Test
    fun `the id lands in D-Store's own bucket so both shells key the same app`() {
        val item = app(slug = "waze").toAppItem()
        val numeric = item.id.substringAfter(':').toLong()
        assertTrue(numeric >= DSTORE_ID_OFFSET)
        assertTrue(com.vythera.vyxelapps.api.isDStoreRepoId(numeric))
        assertTrue(!com.vythera.vyxelapps.api.isZealotRepoId(numeric))
    }

    @Test
    fun `the catalog fields reach the card`() {
        val item = app().toAppItem()
        assertEquals("Example", item.name)
        assertEquals("com.example.app", item.packageName)
        assertEquals("1.2.0", item.version)
        assertEquals("Example Dev", item.author)
        assertEquals("An example app", item.summary)
        assertEquals(SourceId.DStore, item.source)
    }

    @Test
    fun `a blank name falls back to the slug and a non-https icon is dropped`() {
        val item = app(name = "  ", icon = "http://insecure.example/icon.png").toAppItem()
        assertEquals("example", item.name)
        assertNull(item.iconUrl)
    }

    @Test
    fun `an entry with an empty slug still maps to a card in D-Store's bucket`() {
        // Unlike a Zealot entry (which has a numeric id), a D-Store row is keyed by slug;
        // an empty one still hashes to a stable id and must not crash the mapping.
        val item = app(slug = "").toAppItem()
        assertTrue(item.id.substringAfter(':').toLong() >= DSTORE_ID_OFFSET)
    }
}
