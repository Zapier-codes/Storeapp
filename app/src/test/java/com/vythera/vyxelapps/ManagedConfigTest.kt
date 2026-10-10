package com.vythera.vyxelapps

import com.vythera.vyxelapps.enterprise.ManagedConfig
import com.vythera.vyxelapps.enterprise.ManagedConfigRules
import com.vythera.vyxelapps.enterprise.withManaged
import com.vythera.vyxelapps.expressive.data.Settings
import com.vythera.vyxelapps.expressive.data.model.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S-P3: managed configuration is security-relevant and off-device, so the rules are pinned here.
 *
 * The two that matter most: a key the organisation did not set must never change the person's
 * setting, and an organisation-hidden package must survive a "restore all".
 */
class ManagedConfigTest {

    // ── parsing ───────────────────────────────────────────────────────────────

    @Test
    fun blankSourceListIsNotSet() {
        assertNull(ManagedConfigRules.parseSources(""))
        assertNull(ManagedConfigRules.parseSources(null))
        assertNull(ManagedConfigRules.parseSources("   "))
    }

    @Test
    fun noneAndOffMeanNoSources() {
        assertEquals(emptySet<SourceId>(), ManagedConfigRules.parseSources("none"))
        assertEquals(emptySet<SourceId>(), ManagedConfigRules.parseSources("off"))
    }

    @Test
    fun sourceNamesAreCaseInsensitiveAndTrimmed() {
        val parsed = ManagedConfigRules.parseSources(" zealot , fDroid ;GitHub ")
        assertEquals(setOf(SourceId.Zealot, SourceId.FDroid, SourceId.GitHub), parsed)
    }

    @Test
    fun aListWithNoRecognisedNameIsTreatedAsUnset() {
        // A typo must not disable the whole store.
        assertNull(ManagedConfigRules.parseSources("ZealotT,NotASource"))
    }

    @Test
    fun unknownNamesAlongsideKnownOnesAreIgnored() {
        assertEquals(setOf(SourceId.Zealot), ManagedConfigRules.parseSources("Zealot,Bogus"))
    }

    @Test
    fun booleansAcceptTheCommonSpellings() {
        assertEquals(true, ManagedConfigRules.parseBoolean("true"))
        assertEquals(true, ManagedConfigRules.parseBoolean("1"))
        assertEquals(true, ManagedConfigRules.parseBoolean("ON"))
        assertEquals(false, ManagedConfigRules.parseBoolean("false"))
        assertEquals(false, ManagedConfigRules.parseBoolean("no"))
        assertNull(ManagedConfigRules.parseBoolean("maybe"))
        assertNull(ManagedConfigRules.parseBoolean(null))
    }

    @Test
    fun packagesAreSplitOnAnyWhitespaceOrComma() {
        val parsed = ManagedConfigRules.parsePackages("com.a , com.b\ncom.c; com.d")
        assertEquals(setOf("com.a", "com.b", "com.c", "com.d"), parsed)
    }

    // ── fromBundle ────────────────────────────────────────────────────────────

    @Test
    fun anEmptyBundleIsNotManaged() {
        val config = ManagedConfigRules.fromBundle(emptyMap())
        assertFalse(config.isManaged)
        assertNull(config.enabledSources)
        assertNull(config.showDesktopSources)
        assertEquals(emptySet<String>(), config.hiddenPackages)
    }

    @Test
    fun onlyTheSetKeysBecomeManaged() {
        val config = ManagedConfigRules.fromBundle(mapOf("show_desktop_sources" to "false"))
        assertTrue(config.isManaged)
        assertEquals(setOf(ManagedConfigRules.KEY_SHOW_DESKTOP_SOURCES), config.managedKeys)
        assertEquals(false, config.showDesktopSources)
        // The person may still edit the two keys the org did not touch.
        assertTrue(config.freelyEditableKeys.contains(ManagedConfigRules.KEY_ENABLED_SOURCES))
    }

    @Test
    fun anUnparseableBooleanDoesNotCountAsManaged() {
        val config = ManagedConfigRules.fromBundle(
            mapOf("show_desktop_sources" to "sometimes", "enabled_sources" to "Zealot")
        )
        assertNull(config.showDesktopSources)
        assertFalse(config.managedKeys.contains(ManagedConfigRules.KEY_SHOW_DESKTOP_SOURCES))
        assertTrue(config.managedKeys.contains(ManagedConfigRules.KEY_ENABLED_SOURCES))
    }

    // ── folding ───────────────────────────────────────────────────────────────

    @Test
    fun unsetKeysLeaveThePersonChoiceAlone() {
        val mine = Settings(enabledSources = setOf(SourceId.FDroid), showDesktopSources = false)
        val folded = mine.withManaged(ManagedConfigRules.fromBundle(emptyMap()))
        assertEquals(mine, folded)
    }

    @Test
    fun aSetKeyOverridesThePersonChoice() {
        val mine = Settings(enabledSources = setOf(SourceId.FDroid), showDesktopSources = false)
        val folded = mine.withManaged(
            ManagedConfigRules.fromBundle(
                mapOf("enabled_sources" to "Zealot", "show_desktop_sources" to "true")
            )
        )
        assertEquals(setOf(SourceId.Zealot), folded.enabledSources)
        assertEquals(true, folded.showDesktopSources)
    }

    @Test
    fun organisationHiddenPackagesSurviveARestoreAll() {
        val managed = ManagedConfigRules.fromBundle(mapOf("hidden_packages" to "com.corp"))
        val folded = Settings(hiddenPackages = setOf("com.mine")).withManaged(managed)
        assertTrue(folded.hiddenPackages.containsAll(setOf("com.mine", "com.corp")))

        // clearHidden() empties the set and the ViewModel re-applies the managed config, so the
        // organisation's packages return while the person's own stay gone.
        val afterRestore = folded.copy(hiddenPackages = emptySet()).withManaged(managed)
        assertEquals(setOf("com.corp"), afterRestore.hiddenPackages)
    }
}
