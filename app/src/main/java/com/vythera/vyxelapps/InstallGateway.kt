package com.vythera.vyxelapps

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.vythera.vyxelapps.api.Verifier
import com.vythera.vyxelapps.api.VerifierPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Single entry point for launching an install of a downloaded APK — leaf `1.b.i.zo`. Wraps the
 * two previously-duplicated `FileProvider.getUriForFile` + `Intent.ACTION_VIEW` blocks in
 * `AppData.kt` (`downloadAndInstall`'s success branch and `rollbackTo`) behind one function that
 * runs `Verifier` (`1.b.i.zi`) first.
 *
 * **Explicitly not** a `PackageInstaller.Session` rewrite and **explicitly not** Shizuku — both
 * considered and declined, see `HANDOVER.md`'s "Decisions on record". The existing `ACTION_VIEW`
 * path already works on unrooted devices with no extra permissions; this leaf adds a verification
 * gate in front of it, it does not replace the install mechanism itself.
 *
 * **Claims accepted today, in practice: `null`/`null` at both existing call sites, unchanged
 * behavior for every install today.** `ReleaseAsset` — what six of the seven sources' installs
 * already flow through — carries no checksum/fingerprint field to pass in yet, and `rollbackTo`
 * re-installs an already-downloaded file with no separate source claim recorded for it either
 * (`InstallHistoryEntry` has no such field). That's deliberate, not an oversight: this leaf's own
 * scope is the gateway itself, not sourcing real claims into it. `1.a.iv.zi` (held until this leaf
 * landed) is the leaf that starts passing Zealot's real `ZealotVersion.sha256`/`signing_fingerprint`
 * claims through here.
 *
 * **Policy for what a `Verifier.Result` means for whether the install proceeds** (this leaf's own
 * call — nothing upstream has decided this yet):
 *  - [Verifier.Result.Trusted] / [Verifier.Result.PartiallyVerified] / [Verifier.Result.NothingToVerify]
 *    → proceed. A missing claim is not treated as a reason to block: that would put a hard gate in
 *    front of the six sources that have never had one, the day this leaf lands, with no real claim
 *    behind it yet — the opposite of "wraps the existing calls, doesn't replace them." The gate
 *    only has teeth once a source actually supplies a claim to check against.
 *  - [Verifier.Result.ChecksumMismatch] / [Verifier.Result.SignatureMismatch] → blocked. This is the
 *    actual protection this leaf exists to add: once a claim *is* supplied, a real mismatch must
 *    stop the install outright, not just get logged somewhere nobody reads.
 *  - [Verifier.Result.Unreadable] / [Verifier.Result.Unparsable] → blocked. Both mean the file
 *    itself couldn't be read or parsed well enough to even run the check the caller asked for —
 *    "nothing mismatched" isn't true here, there was nothing to compare in the first place, so this
 *    gateway does not wave it through.
 *
 * Verification runs on [Dispatchers.IO] (checksum hashing can walk tens of megabytes of file);
 * `startActivity` runs back on the caller's own dispatcher, same as every other UI-facing call in
 * `AppData.kt`. With both claims `null` (today's only real call pattern), `Verifier.verify` never
 * touches disk at all — the IO hop is a no-op in practice until `1.a.iv.zi` starts supplying claims.
 */
sealed class InstallOutcome {
    data class Started(val verify: Verifier.Result) : InstallOutcome()
    data class Blocked(val verify: Verifier.Result, val reason: String) : InstallOutcome()
}

object InstallGateway {

    suspend fun install(
        ctx: Context,
        apkFile: File,
        claimedSha256: String? = null,
        claimedSigningFingerprint: String? = null
    ): InstallOutcome {
        val result = withContext(Dispatchers.IO) {
            Verifier.verify(ctx, apkFile, claimedSha256, claimedSigningFingerprint)
        }

        // j.vii.a: the policy lives in VerifierPolicy so the Expressive shell applies the very same rule.
        val blockedReason = VerifierPolicy.blockedReason(result)

        if (blockedReason != null) return InstallOutcome.Blocked(result, blockedReason)

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", apkFile)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return InstallOutcome.Started(result)
    }
}
