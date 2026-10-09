package com.vythera.vyxelapps.api

/**
 * Track h, leaf `h.iii.zi`: the pure rules of the store installing its own update through a
 * `PackageInstaller.Session`. Nothing here touches Android, the disk or the network, so every rule runs as a
 * plain unit test; `SelfInstaller.kt` and `SelfUpdateReceiver.kt` do the Android calls and ask these functions.
 */

/** What the receiver and the installer report, for the banner (leaf h.iii.zo) to show. `versionCode` ties a state to one offer. */
sealed class SelfInstallState {
    object Idle : SelfInstallState()
    /** The session is committed and Android is installing, or about to ask. */
    data class Running(val versionCode: Long) : SelfInstallState()
    /** Android's own confirmation screen is open; the user has to answer it. */
    data class AwaitingConfirmation(val versionCode: Long) : SelfInstallState()
    data class Succeeded(val versionCode: Long) : SelfInstallState()
    /** [reason] is a plain sentence that includes Android's own message text when it gave one. */
    data class Failed(val versionCode: Long, val reason: String) : SelfInstallState()
}

/** Is the downloaded file signed by the same key as the store that is installed now? */
sealed class SignerComparison {
    object Same : SignerComparison()
    data class Different(val installed: Set<String>, val candidate: Set<String>) : SignerComparison()
    data class Unknown(val reason: String) : SignerComparison()
}

/** What one `PackageInstaller` status broadcast means. */
sealed class StatusVerdict {
    /** `STATUS_PENDING_USER_ACTION`: the receiver must launch the confirmation screen it carries. */
    object NeedsConfirmation : StatusVerdict()
    object Success : StatusVerdict()
    data class Failed(val reason: String) : StatusVerdict()
}

object SelfInstallRules {

    // The numbers are PackageInstaller's STATUS_* constants. They are written out here, not imported, so this file
    // stays free of Android and testable; they are part of the public API and do not change.
    const val STATUS_PENDING_USER_ACTION = -1
    const val STATUS_SUCCESS = 0
    const val STATUS_FAILURE = 1
    const val STATUS_FAILURE_BLOCKED = 2
    const val STATUS_FAILURE_ABORTED = 3
    const val STATUS_FAILURE_INVALID = 4
    const val STATUS_FAILURE_CONFLICT = 5
    const val STATUS_FAILURE_STORAGE = 6
    const val STATUS_FAILURE_INCOMPATIBLE = 7
    const val STATUS_FAILURE_TIMEOUT = 8

    /**
     * Compares the certificates (SHA-256 fingerprints, any case or separator) of the installed store with those
     * of the downloaded file. The two sets must share **at least one** fingerprint to be [SignerComparison.Same]:
     * a key rotation can leave a different current set that Android still accepts through its lineage, and
     * refusing that here would block a valid update. Android makes the final call; this check exists to give a
     * clear message for the plain case of a file signed with an unrelated key, before a session is opened.
     */
    fun compareSigners(installed: List<String>?, candidate: List<String>?): SignerComparison {
        val a = normalize(installed)
        val b = normalize(candidate)
        if (a.isEmpty()) return SignerComparison.Unknown("the installed store's signing certificate could not be read")
        if (b.isEmpty()) return SignerComparison.Unknown("the downloaded file's signing certificate could not be read")
        return if (a.any { it in b }) SignerComparison.Same else SignerComparison.Different(a, b)
    }

    /** On Android 12 (API 31) and newer a later update may skip the confirmation; Android 8 to 11 always confirm. */
    fun requireNoUserAction(sdk: Int): Boolean = sdk >= 31

    /**
     * Turns a status broadcast into a verdict. For a failure the sentence says what the status means in plain
     * words **and quotes Android's own message** (the real `INSTALL_FAILED_*` or `INSTALL_PARSE_FAILED_*` text
     * the generic "problem parsing the package" screen hides), so the reason is never lost.
     */
    fun describeStatus(status: Int, androidMessage: String?): StatusVerdict {
        if (status == STATUS_PENDING_USER_ACTION) return StatusVerdict.NeedsConfirmation
        if (status == STATUS_SUCCESS) return StatusVerdict.Success
        val meaning = when (status) {
            STATUS_FAILURE_ABORTED -> "The update was cancelled."
            STATUS_FAILURE_BLOCKED -> "Android blocked the update. A device policy or another app, such as a security scanner, may be blocking installs."
            STATUS_FAILURE_INVALID -> "Android says the update file is not a valid package."
            STATUS_FAILURE_CONFLICT -> "The update conflicts with an app or version already installed."
            STATUS_FAILURE_STORAGE -> "There is not enough storage to install the update."
            STATUS_FAILURE_INCOMPATIBLE -> "Android says the update is not compatible with this device."
            STATUS_FAILURE_TIMEOUT -> "The install timed out."
            STATUS_FAILURE -> "Android could not install the update."
            else -> "Android reported install status $status."
        }
        val said = androidMessage?.trim().orEmpty()
        return StatusVerdict.Failed(if (said.isEmpty()) meaning else "$meaning Android said: $said")
    }

    private fun normalize(raw: List<String>?): Set<String> =
        raw.orEmpty()
            .map {
                it.trim().removePrefix("sha256:").removePrefix("SHA256:").removePrefix("Sha256:")
                    .replace(":", "").replace(" ", "").lowercase()
            }
            .filter { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            .toSet()
}
