package com.vythera.vyxelapps

import com.vythera.vyxelapps.expressive.data.model.SourceId
import com.vythera.vyxelapps.expressive.data.scanSourceToSourceId
import com.vythera.vyxelapps.expressive.data.toAppItem
import com.vythera.vyxelapps.updater.DStoreUpdaterSource
import com.vythera.vyxelapps.updater.ScanLink
import com.vythera.vyxelapps.updater.ZealotUpdaterSource
import com.vythera.vyxelapps.updater.storeScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaf j.vii.d: the pure part of the store-first Expressive Updates tab — a row built from
 * the shared store check is badged as its store (never as a repo) and carries a download
 * link only for an installable (signed Zealot) update.
 *
 * The engine's network routing (`UpdateScanEngine.storeUpdates` calling `StoreUpdateChecker`)
 * is not exercised here — it needs a device and a live index.
 */
class StoreScanResultTest {

    @Test
    fun `a Zealot row is badged Zealot and wears the signed download link`() {
        val row = storeScanResult(
            packageName    = "com.example.app",
            appName        = "Example",
            currentVersion = "1.1.13",
            newVersion     = "1.1.14",
            source         = ZealotUpdaterSource,
            link           = ScanLink.Url("https://zealot.example/download/releases/8"),
        )
        assertEquals(SourceId.Zealot, scanSourceToSourceId(row.source))
        assertEquals(SourceId.Zealot, row.toAppItem().source)
        assertTrue(row.hasUpdate)
        assertEquals("https://zealot.example/download/releases/8", (row.link as ScanLink.Url).link)
    }

    @Test
    fun `a D-Store row is badged D-Store and offers no download link`() {
        val row = storeScanResult(
            packageName    = "com.example.app",
            appName        = "Example",
            currentVersion = "2.0",
            newVersion     = "2.1",
            source         = DStoreUpdaterSource,
        )
        assertEquals(SourceId.DStore, scanSourceToSourceId(row.source))
        assertEquals(SourceId.DStore, row.toAppItem().source)
        // No link, so the engine can never hand this to an installer (decision 5a/5b).
        assertTrue(row.link is ScanLink.Empty)
    }

    @Test
    fun `a blank name falls back to the package name`() {
        val row = storeScanResult(
            packageName    = "com.example.app",
            appName        = "",
            currentVersion = "1.0",
            newVersion     = "1.1",
            source         = ZealotUpdaterSource,
            link           = ScanLink.Url("https://zealot.example/x"),
        )
        assertEquals("com.example.app", row.appName)
        assertEquals("1.0  →  1.1", row.toAppItem().summary)
    }
}
