package com.vythera.vyxelapps.silent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Z-P26: the silent-install decisions, tested without a device.
 *
 * Everything here is the pure half of the feature — the ordering/eligibility rule and the `pm`
 * command/result parsing. The probing and the `pm` runs themselves need a device and are not covered
 * here; the rule is what decides whether those runs ever happen.
 */
class SilentInstallRulesTest {

    private fun statuses(vararg ready: SilentInstallBackend): (SilentInstallBackend) -> SilentInstallStatus = { backend ->
        if (backend in ready) SilentInstallStatus.Ready else SilentInstallStatus.NotInstalled
    }

    @Test
    fun disabledMeansNoSilentInstallEvenWhenEverythingIsReady() {
        val plan = SilentInstallRules.plan(
            enabled = false,
            pinned = null,
            status = statuses(SilentInstallBackend.Shizuku, SilentInstallBackend.Dhizuku, SilentInstallBackend.Root),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun enabledPrefersShizukuThenDhizukuThenRoot() {
        val plan = SilentInstallRules.plan(
            enabled = true,
            pinned = null,
            status = statuses(SilentInstallBackend.Shizuku, SilentInstallBackend.Dhizuku, SilentInstallBackend.Root),
        )
        assertEquals(
            listOf(SilentInstallBackend.Shizuku, SilentInstallBackend.Dhizuku, SilentInstallBackend.Root),
            plan,
        )
    }

    @Test
    fun onlyReadyBackendsAreConsidered() {
        val plan = SilentInstallRules.plan(
            enabled = true,
            pinned = null,
            status = statuses(SilentInstallBackend.Root),
        )
        assertEquals(listOf(SilentInstallBackend.Root), plan)
    }

    @Test
    fun nothingReadyMeansFallBackToAndroid() {
        val plan = SilentInstallRules.plan(enabled = true, pinned = null, status = statuses())
        assertTrue(plan.isEmpty())
    }

    @Test
    fun aPinUsesOnlyThatBackend() {
        val plan = SilentInstallRules.plan(
            enabled = true,
            pinned = SilentInstallBackend.Dhizuku,
            status = statuses(SilentInstallBackend.Shizuku, SilentInstallBackend.Dhizuku, SilentInstallBackend.Root),
        )
        assertEquals(listOf(SilentInstallBackend.Dhizuku), plan)
    }

    @Test
    fun aPinThatIsNotReadyDoesNotFallThroughToAnotherPrivilege() {
        // The person picked Dhizuku; Shizuku is ready but must NOT be used behind their back.
        val plan = SilentInstallRules.plan(
            enabled = true,
            pinned = SilentInstallBackend.Dhizuku,
            status = statuses(SilentInstallBackend.Shizuku),
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun aRunningButUngrantedBackendIsNotReady() {
        val plan = SilentInstallRules.plan(
            enabled = true,
            pinned = null,
            status = { if (it == SilentInstallBackend.Shizuku) SilentInstallStatus.Running else SilentInstallStatus.NotInstalled },
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun unknownStatusIsNotReady() {
        val plan = SilentInstallRules.plan(
            enabled = true,
            pinned = null,
            status = { SilentInstallStatus.Unknown },
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun readyFlagsOnlyTheReadyCase() {
        assertTrue(SilentInstallStatus.Ready.ready)
        assertFalse(SilentInstallStatus.Running.ready)
        assertFalse(SilentInstallStatus.NotInstalled.ready)
        assertFalse(SilentInstallStatus.Unknown.ready)
    }

    @Test
    fun silentInstallIsOffByDefault() {
        assertFalse(SilentInstallRules.ENABLED_BY_DEFAULT)
    }

    @Test
    fun setupMapsEachBackendToItsPackage() {
        assertEquals("moe.shizuku.privileged.api", SilentInstallSetup.of(SilentInstallBackend.Shizuku).packageName)
        assertEquals("com.rosan.dhizuku", SilentInstallSetup.of(SilentInstallBackend.Dhizuku).packageName)
        assertEquals(null, SilentInstallSetup.of(SilentInstallBackend.Root).packageName)
    }
}
