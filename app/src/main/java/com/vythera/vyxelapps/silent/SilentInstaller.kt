package com.vythera.vyxelapps.silent

import android.content.Context
import android.util.Log
import com.vythera.vyxelapps.ShizukuHelper
import com.vythera.vyxelapps.root.RootAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Z-P26: drives the silent-install backends the person has enabled.
 *
 * It probes each backend ([status]) and, when asked to install, runs `pm` through the highest-priority
 * backend that is ready ([SilentInstallRules.plan]). Every path ends at the same `pm install`, so a
 * failure is reported in the same words whichever backend produced it, and a backend that is not ready
 * is simply not tried — the caller then falls back to Android's own installer, which asks the person.
 *
 * Nothing here installs or bundles a second app. Shizuku, Dhizuku and `su` are services the person
 * already runs (or sets up through their own app, [SilentInstallSetup]); the store only borrows the
 * privilege they expose, and only after the person has switched silent installs on.
 *
 * Every call that touches another process runs on [Dispatchers.IO] and is bounded by [TIMEOUT_MS], so a
 * backend that hangs (a manager that never answers, a `su` prompt left open) cannot wedge the install
 * flow — it times out and the caller falls back.
 */
object SilentInstaller {

    private const val TAG = "VyxelSilent"
    private const val TIMEOUT_MS = 60_000L
    private const val PROBE_TIMEOUT_MS = 6_000L

    // ── Probing ────────────────────────────────────────────────────────────────

    suspend fun status(context: Context, backend: SilentInstallBackend): SilentInstallStatus =
        when (backend) {
            SilentInstallBackend.Shizuku -> shizukuStatus()
            SilentInstallBackend.Dhizuku -> dhizukuStatus(context)
            SilentInstallBackend.Root -> rootStatus()
        }

    private suspend fun shizukuStatus(): SilentInstallStatus = withContext(Dispatchers.IO) {
        try {
            if (!com.vythera.vyxelapps.ShizukuInstaller.isAvailable()) return@withContext SilentInstallStatus.NotInstalled
            if (!com.vythera.vyxelapps.ShizukuInstaller.hasPermission()) return@withContext SilentInstallStatus.Running
            SilentInstallStatus.Ready
        } catch (e: Throwable) {
            Log.d(TAG, "shizuku probe failed: ${e.message}")
            SilentInstallStatus.Unknown
        }
    }

    private suspend fun dhizukuStatus(context: Context): SilentInstallStatus = withContext(Dispatchers.IO) {
        try {
            val app = context.applicationContext
            if (!DhizukuBridge.init(app)) return@withContext SilentInstallStatus.NotInstalled
            if (!DhizukuBridge.isPermissionGranted()) return@withContext SilentInstallStatus.Running
            SilentInstallStatus.Ready
        } catch (e: Throwable) {
            Log.d(TAG, "dhizuku probe failed: ${e.message}")
            SilentInstallStatus.Unknown
        }
    }

    private suspend fun rootStatus(): SilentInstallStatus = withContext(Dispatchers.IO) {
        try {
            if (RootAccess.isAvailable()) SilentInstallStatus.Ready else SilentInstallStatus.NotInstalled
        } catch (e: Throwable) {
            Log.d(TAG, "root probe failed: ${e.message}")
            SilentInstallStatus.Unknown
        }
    }

    /** Asks a backend for its permission, when it needs one. Returns immediately; the dialog is the backend's. */
    fun requestPermission(context: Context, backend: SilentInstallBackend) {
        when (backend) {
            SilentInstallBackend.Shizuku ->
                runCatching { com.vythera.vyxelapps.ShizukuInstaller.requestPermission(REQUEST_SHIZUKU) }
            SilentInstallBackend.Dhizuku ->
                runCatching { DhizukuBridge.requestPermission(context.applicationContext) }
            SilentInstallBackend.Root -> Unit // no permission to ask for; `su` prompts on first use
        }
    }

    // ── Installing ─────────────────────────────────────────────────────────────

    sealed class Result {
        /** Installed by [backend] with no confirmation screen. */
        data class Success(val backend: SilentInstallBackend) : Result()

        /** The backend ran and `pm` refused (bad APK, version downgrade, signature mismatch). [reason] is `pm`'s line. */
        data class Failed(val backend: SilentInstallBackend, val reason: String) : Result()

        /** No backend was ready; the caller must fall back to Android's installer. */
        object NoBackend : Result()
    }

    /**
     * Installs [apk], trying the ready backends in order. The first success stops the sequence; the
     * first *failure* also stops it (a refused `pm install` will be refused by every backend, so
     * retrying with more privilege only wastes time), and is reported as-is.
     */
    suspend fun install(
        context: Context,
        apk: File,
        enabled: Boolean,
        pinned: SilentInstallBackend?,
    ): Result {
        // Probe once, up front, so the pure `plan` can be called without a suspend lambda.
        val statuses = SilentInstallBackend.entries.associateWith { status(context, it) }
        val plan = SilentInstallRules.plan(enabled, pinned) { statuses[it] ?: SilentInstallStatus.Unknown }
        if (plan.isEmpty()) return Result.NoBackend

        return when (val backend = plan.first()) {
            SilentInstallBackend.Shizuku -> runShizuku(apk)
            SilentInstallBackend.Dhizuku -> runDhizuku(context, apk)
            SilentInstallBackend.Root -> runRoot(apk)
        }
    }

    /** Uninstalls [packageName] through the first ready backend, or null when none is ready. */
    suspend fun uninstall(
        context: Context,
        packageName: String,
        enabled: Boolean,
        pinned: SilentInstallBackend?,
    ): Boolean {
        if (!enabled) return false
        val backends = if (pinned != null) listOf(pinned) else SilentInstallRules.DEFAULT_ORDER
        for (backend in backends) {
            if (!status(context, backend).ready) continue
            val argv = SilentInstallCommand.uninstallArgv(packageName)
            val output = when (backend) {
                SilentInstallBackend.Shizuku -> runShizukuProcess(argv, null)
                SilentInstallBackend.Dhizuku -> runDhizukuProcess(context, argv, null)
                SilentInstallBackend.Root -> RootAccess.runCommand(argv, TIMEOUT_MS)
            }
            if (output != null && SilentInstallCommand.succeeded(0, output)) return true
        }
        return false
    }

    // ── Per-backend plumbing ───────────────────────────────────────────────────

    private suspend fun runShizuku(apk: File): Result = withContext(Dispatchers.IO) {
        val argv = SilentInstallCommand.installArgv(apk.length())
        val output = runShizukuProcess(argv, apk)
            ?: return@withContext Result.Failed(SilentInstallBackend.Shizuku, "Shizuku did not answer")
        if (SilentInstallCommand.succeeded(0, output)) Result.Success(SilentInstallBackend.Shizuku)
        else Result.Failed(SilentInstallBackend.Shizuku, SilentInstallCommand.failureReason(output, "install refused"))
    }

    private suspend fun runDhizuku(context: Context, apk: File): Result = withContext(Dispatchers.IO) {
        val argv = SilentInstallCommand.installArgv(apk.length())
        val output = runDhizukuProcess(context, argv, apk)
            ?: return@withContext Result.Failed(SilentInstallBackend.Dhizuku, "Dhizuku did not answer")
        if (SilentInstallCommand.succeeded(0, output)) Result.Success(SilentInstallBackend.Dhizuku)
        else Result.Failed(SilentInstallBackend.Dhizuku, SilentInstallCommand.failureReason(output, "install refused"))
    }

    private suspend fun runRoot(apk: File): Result = withContext(Dispatchers.IO) {
        // Root reads the store's private file itself: copy it somewhere `pm` can read, install, clean
        // up. `pm install -S` over `su`'s stdin is unreliable across su implementations, and a copy is
        // the one form every rooted device agrees on.
        val tmp = "/data/local/tmp/vyxel-install-${System.currentTimeMillis()}.apk"
        val script = "cp '${apk.absolutePath}' '$tmp' && pm install -r --user 0 '$tmp'; rm -f '$tmp'"
        val output = RootAccess.runCommand(listOf("sh", "-c", script), TIMEOUT_MS)
            ?: return@withContext Result.Failed(SilentInstallBackend.Root, "root refused")
        if (SilentInstallCommand.succeeded(0, output)) Result.Success(SilentInstallBackend.Root)
        else Result.Failed(SilentInstallBackend.Root, SilentInstallCommand.failureReason(output, "install refused"))
    }

    private fun runShizukuProcess(argv: List<String>, stdin: File?): String? = try {
        val process = ShizukuHelper.newProcess(argv.toTypedArray())
        pump(process, stdin)
    } catch (e: Throwable) {
        Log.d(TAG, "shizuku process failed: ${e.message}")
        null
    }

    private fun runDhizukuProcess(context: Context, argv: List<String>, stdin: File?): String? = try {
        val process = DhizukuBridge.newProcess(context.applicationContext, argv)
        if (process == null) null else pump(process, stdin)
    } catch (e: Throwable) {
        Log.d(TAG, "dhizuku process failed: ${e.message}")
        null
    }

    /**
     * Feeds [stdin] (when given) into [process], waits for it, and returns its combined output.
     *
     * A null return means the process never finished inside [TIMEOUT_MS]; it is destroyed so the
     * backend is not left holding an open session.
     */
    private fun pump(process: Process, stdin: File?): String? {
        try {
            if (stdin != null) {
                process.outputStream.buffered().use { sink ->
                    stdin.inputStream().buffered().use { it.copyTo(sink) }
                }
            } else {
                runCatching { process.outputStream.close() }
            }
            if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                runCatching { process.destroy() }
                return null
            }
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            return (out + "\n" + err).trim()
        } catch (e: Throwable) {
            runCatching { process.destroy() }
            Log.d(TAG, "process pump failed: ${e.message}")
            return null
        }
    }

    private const val REQUEST_SHIZUKU = 1002
}
