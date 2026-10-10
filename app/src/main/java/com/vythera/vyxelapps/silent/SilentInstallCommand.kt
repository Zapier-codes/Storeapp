package com.vythera.vyxelapps.silent

/**
 * Z-P26: the one command every backend runs, kept pure so it is unit-tested without a device.
 *
 * `pm install -r --user 0 -S <size>` is the streaming form: `-S` says "the APK arrives on stdin", so
 * the shell user never has to read the store's private file path — which is exactly the permission
 * problem that makes a plain `pm install <path>` fail on app-private storage. `-r` reinstalls (an
 * update), and `--user 0` targets the owner, the only user an enterprise or a single-user phone has.
 */
object SilentInstallCommand {

    fun installArgv(sizeBytes: Long): List<String> = listOf(
        "pm", "install", "-r", "--user", "0", "-S", sizeBytes.toString(),
    )

    fun uninstallArgv(packageName: String): List<String> = listOf(
        "pm", "uninstall", "--user", "0", packageName,
    )

    /**
     * Whether a finished `pm install` succeeded.
     *
     * `pm` is inconsistent about the exit code across releases and backends, so the printed result is
     * read as well: a line containing "Success" is a success even when the process returned non-zero,
     * and the absence of it is a failure even when it returned zero.
     */
    fun succeeded(exitCode: Int, output: String): Boolean =
        exitCode == 0 || output.contains("Success", ignoreCase = true)

    /** The most useful line to show when a command failed: the first `Failure [...]` / `Error:`, else the whole output. */
    fun failureReason(output: String, fallback: String): String {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return fallback
        return line.ifBlank { fallback }
    }
}
