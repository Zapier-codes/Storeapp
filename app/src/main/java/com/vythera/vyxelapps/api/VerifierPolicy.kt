package com.vythera.vyxelapps.api

/**
 * Track j, leaf `j.vii.a`: the one rule for what a [Verifier.Result] means for whether an install may go on, shared
 * by both shells. Classic's `InstallGateway` had it inline; the Expressive shell had no check at all (it handed the
 * downloaded file straight to `PackageInstaller` or Shizuku), so a claim a store published was never compared with
 * the file that arrived. Pure (no Android), so it runs as a plain unit test.
 *
 * Policy, unchanged from `InstallGateway` (decided in leaf `1.b.i.zo`): a real mismatch, or a file that could not be
 * read or parsed well enough to run the check the caller asked for, blocks the install. A MISSING claim does not
 * block ([Verifier.Result.PartiallyVerified], [Verifier.Result.NothingToVerify]): the sources that have never
 * published one keep working, and the gate has teeth exactly where a source supplies a claim (Zealot's signed index).
 */
object VerifierPolicy {

    /** A plain sentence for the person when the install must stop, or null when it may go on. */
    fun blockedReason(result: Verifier.Result): String? = when (result) {
        is Verifier.Result.ChecksumMismatch ->
            "Downloaded file's checksum did not match what the source published — refusing to install."
        is Verifier.Result.SignatureMismatch ->
            "Downloaded file's signing certificate did not match what the source published — refusing to install."
        is Verifier.Result.Unreadable ->
            "Could not read the downloaded file to verify it (${result.reason}) — refusing to install."
        is Verifier.Result.Unparsable ->
            "Could not parse the downloaded file to verify its signing certificate (${result.reason}) — refusing to install."
        is Verifier.Result.Trusted, is Verifier.Result.PartiallyVerified, is Verifier.Result.NothingToVerify -> null
    }
}
