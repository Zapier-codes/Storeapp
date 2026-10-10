package com.vythera.vyxelapps

import com.vythera.vyxelapps.api.UpdateOwnershipRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 47i. Expected values come from the rule in `api/UpdateOwnership.kt` and Android's API-34 documentation. */
class UpdateOwnershipRulesTest {

    @Test
    fun `the first Android that has the call is 34`() {
        assertEquals(34, UpdateOwnershipRules.MIN_SDK)
    }

    @Test
    fun `android 14 and newer ask when the switch is on`() {
        assertTrue(UpdateOwnershipRules.shouldRequest(34, enabled = true))
        assertTrue(UpdateOwnershipRules.shouldRequest(36, enabled = true))
    }

    @Test
    fun `older android never asks, whatever the switch`() {
        assertFalse(UpdateOwnershipRules.shouldRequest(33, enabled = true))
        assertFalse(UpdateOwnershipRules.shouldRequest(26, enabled = true))
    }

    @Test
    fun `the switch off means no session asks`() {
        assertFalse(UpdateOwnershipRules.shouldRequest(34, enabled = false))
        assertFalse(UpdateOwnershipRules.shouldRequest(36, enabled = false))
    }

    @Test
    fun `the default follows the constant`() {
        assertEquals(UpdateOwnershipRules.REQUEST_OWNERSHIP, UpdateOwnershipRules.shouldRequest(36))
        assertFalse(UpdateOwnershipRules.shouldRequest(30))
    }
}
