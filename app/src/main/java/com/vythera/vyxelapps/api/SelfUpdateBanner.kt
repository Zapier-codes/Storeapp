package com.vythera.vyxelapps.api

/**
 * Track h, leaf `h.ii.zo`: the pure model of the self-update banner. What the banner says is decided here from
 * three inputs (the offer, the download's state and the check phase), so the whole mapping is unit-tested and
 * the composable only draws the result. Nothing here touches Android or the disk.
 *
 * The states, in the order a user sees them: Available, Downloading (percent, Cancel), Verifying, ReadyToInstall,
 * and Failed (a plain reason and Retry). The dismiss button is not a state; it hides the banner and is kept.
 */
sealed class SelfUpdateBannerState {
    data class Available(val versionName: String, val changelogLine: String?) : SelfUpdateBannerState()
    data class Downloading(val versionName: String, val percent: Int) : SelfUpdateBannerState()
    data class Verifying(val versionName: String) : SelfUpdateBannerState()
    data class ReadyToInstall(val versionName: String) : SelfUpdateBannerState()
    data class Failed(val versionName: String, val reason: String) : SelfUpdateBannerState()
}

/**
 * Where the downloaded file is in the checks that come after the size test (checksum and signing certificate).
 * Owned by the view model; [versionCode] ties it to one offer so a stale phase of an older offer is ignored.
 */
sealed class SelfUpdateCheckPhase {
    object None : SelfUpdateCheckPhase()
    data class Verifying(val versionCode: Long) : SelfUpdateCheckPhase()
    data class Ready(val versionCode: Long, val filePath: String) : SelfUpdateCheckPhase()
    data class Failed(val versionCode: Long, val reason: String) : SelfUpdateCheckPhase()
}

object SelfUpdateBanner {

    private const val CHANGELOG_LINE_MAX = 60

    /** The first non-blank line of [changelog], trimmed and cut to 60 characters with an ellipsis; null if there is none. */
    fun firstChangelogLine(changelog: String?): String? {
        val line = changelog?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
        return if (line.length <= CHANGELOG_LINE_MAX) line else line.take(CHANGELOG_LINE_MAX - 1).trimEnd() + "\u2026"
    }

    /**
     * What the banner shows for one offer. A phase or download state that belongs to a different `versionCode`
     * is ignored. The check phase wins over the download state, because it comes later in the flow. A finished
     * download with no phase yet is "Verifying": the checks start as soon as the file is accepted.
     */
    fun stateFor(
        versionName: String,
        versionCode: Long,
        changelog: String?,
        download: SelfUpdateDownloadState,
        phase: SelfUpdateCheckPhase
    ): SelfUpdateBannerState {
        when (phase) {
            is SelfUpdateCheckPhase.Failed -> if (phase.versionCode == versionCode) {
                return SelfUpdateBannerState.Failed(versionName, phase.reason)
            }
            is SelfUpdateCheckPhase.Ready -> if (phase.versionCode == versionCode) {
                return SelfUpdateBannerState.ReadyToInstall(versionName)
            }
            is SelfUpdateCheckPhase.Verifying -> if (phase.versionCode == versionCode) {
                return SelfUpdateBannerState.Verifying(versionName)
            }
            SelfUpdateCheckPhase.None -> Unit
        }
        return when (download) {
            is SelfUpdateDownloadState.Failed -> if (download.versionCode == versionCode) {
                SelfUpdateBannerState.Failed(versionName, download.reason)
            } else available(versionName, changelog)
            is SelfUpdateDownloadState.Downloading -> if (download.versionCode == versionCode) {
                SelfUpdateBannerState.Downloading(versionName, download.percent)
            } else available(versionName, changelog)
            is SelfUpdateDownloadState.Downloaded -> if (download.versionCode == versionCode) {
                SelfUpdateBannerState.Verifying(versionName)
            } else available(versionName, changelog)
            SelfUpdateDownloadState.Idle -> available(versionName, changelog)
        }
    }

    private fun available(versionName: String, changelog: String?) =
        SelfUpdateBannerState.Available(versionName, firstChangelogLine(changelog))

    /**
     * The phase after [Verifier] has run on the downloaded file against the index's `sha256` and
     * `signing_fingerprint`. **Only a result that checked BOTH is Ready**; a partial or empty check is a failure,
     * because the planner guarantees both claims exist, so a missing check means something went wrong.
     * A failed phase means the caller deletes the file.
     */
    fun phaseFor(result: Verifier.Result, versionCode: Long, filePath: String): SelfUpdateCheckPhase = when (result) {
        is Verifier.Result.Trusted ->
            if (result.checked.containsAll(Verifier.Check.values().toList())) {
                SelfUpdateCheckPhase.Ready(versionCode, filePath)
            } else {
                SelfUpdateCheckPhase.Failed(versionCode, "The update could not be fully checked, so it was not installed.")
            }
        is Verifier.Result.ChecksumMismatch ->
            SelfUpdateCheckPhase.Failed(versionCode, "The downloaded update did not match its published checksum, so it was discarded.")
        is Verifier.Result.SignatureMismatch ->
            SelfUpdateCheckPhase.Failed(versionCode, "The downloaded update was not signed by the published certificate, so it was discarded.")
        is Verifier.Result.Unreadable ->
            SelfUpdateCheckPhase.Failed(versionCode, "The downloaded update could not be read to check it (${result.reason}).")
        is Verifier.Result.Unparsable ->
            SelfUpdateCheckPhase.Failed(versionCode, "The downloaded update could not be opened to check its certificate (${result.reason}).")
        is Verifier.Result.PartiallyVerified, is Verifier.Result.NothingToVerify ->
            SelfUpdateCheckPhase.Failed(versionCode, "The update could not be fully checked, so it was not installed.")
    }
}
