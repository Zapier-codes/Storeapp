package com.vythera.vyxelapps.silent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Z-P26: the exact `pm` invocation and how its result is read, without a device. */
class SilentInstallCommandTest {

    @Test
    fun installStreamsTheApkOnStdin() {
        assertEquals(
            listOf("pm", "install", "-r", "--user", "0", "-S", "1048576"),
            SilentInstallCommand.installArgv(1048576L),
        )
    }

    @Test
    fun uninstallTargetsTheOwnerUser() {
        assertEquals(
            listOf("pm", "uninstall", "--user", "0", "com.example.app"),
            SilentInstallCommand.uninstallArgv("com.example.app"),
        )
    }

    @Test
    fun zeroExitIsSuccess() {
        assertTrue(SilentInstallCommand.succeeded(0, ""))
    }

    @Test
    fun printedSuccessCountsEvenWithANonZeroExit() {
        // pm is inconsistent about the exit code across releases; the printed result is authoritative.
        assertTrue(SilentInstallCommand.succeeded(1, "Success"))
    }

    @Test
    fun noPrintedSuccessAndNonZeroIsFailure() {
        assertFalse(SilentInstallCommand.succeeded(1, "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
    }

    @Test
    fun failureReasonIsTheFirstNonBlankLine() {
        val output = "\nFailure [INSTALL_FAILED_INVALID_APK]\nmore"
        assertEquals("Failure [INSTALL_FAILED_INVALID_APK]", SilentInstallCommand.failureReason(output, "fallback"))
    }

    @Test
    fun failureReasonFallsBackWhenOutputIsBlank() {
        assertEquals("fallback", SilentInstallCommand.failureReason("\n  \n", "fallback"))
    }
}
