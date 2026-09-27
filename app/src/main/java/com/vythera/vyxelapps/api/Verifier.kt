package com.vythera.vyxelapps.api

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Downloaded-artifact verification primitives — leaf `1.b.i.zi`. Deliberately its own file,
 * same split as `ZealotTrust.kt` vs `ZealotClient`: this is pure verification logic with no I/O
 * side effects beyond reading the one file it's asked to check, and no install call of its own.
 * Wiring this into the two existing `FileProvider`+`ACTION_VIEW` call sites in `AppData.kt` is
 * `1.b.i.zo` (`InstallGateway`) — out of this leaf's scope. `1.a.iv.zi` (routing Zealot installs
 * through that gateway) is held until `1.b.i.zo` lands, per `HANDOVER.md`.
 *
 * Two independent checks, matching this leaf's own description:
 *  - SHA-256 of the downloaded bytes vs. a source-claimed checksum.
 *  - SHA-256 fingerprint of the archive's signing certificate(s) vs. a source-claimed fingerprint.
 * "Independent" means each check runs and fails on its own terms — a checksum mismatch is
 * reported without ever touching `PackageManager`, and a fingerprint mismatch is reported without
 * re-hashing the file a second time. Neither check's absence of a claim is silently treated as
 * pass *or* fail: `HANDOVER.md`'s own note that "no source verifies anything today" means most
 * callers today will have nothing to hand this function for one or both claims (`ReleaseAsset`,
 * the model every non-Zealot source's `ACTION_VIEW` install path already uses, carries no
 * checksum/fingerprint field at all — only `ZealotVersion.sha256`/`signing_fingerprint` do, as of
 * `1.a.ii.zo`). What an unverifiable source *means* for whether an install proceeds is an install
 * policy decision, and belongs to `1.b.i.zo`'s `InstallGateway`, not this leaf — so a missing claim
 * surfaces as its own result ([NothingToVerify] / [PartiallyVerified]), never collapsed into
 * [Trusted] or a mismatch.
 */
object Verifier {

    enum class Check { CHECKSUM, SIGNING_FINGERPRINT }

    sealed class Result {
        /** Every claim the caller supplied was checked and matched. */
        data class Trusted(val checked: Set<Check>) : Result()
        /** At least one claim was supplied and matched, but at least one other was absent — not a
         *  failure, but not a full [Trusted] verdict either. Caller decides the policy. */
        data class PartiallyVerified(val checked: Set<Check>, val missing: Set<Check>) : Result()
        /** Neither claim was supplied — nothing here for this function to compare against. */
        data class NothingToVerify(val missing: Set<Check>) : Result()
        /** Downloaded bytes' SHA-256 does not match the source-claimed checksum. */
        data class ChecksumMismatch(val expected: String, val actual: String) : Result()
        /** None of the archive's current signing-certificate fingerprints match the source-claimed one. */
        data class SignatureMismatch(val expected: String, val actual: List<String>) : Result()
        /** The file could not be read off disk to compute a checksum (missing, IO error, ...). */
        data class Unreadable(val reason: String) : Result()
        /** `PackageManager` could not parse the archive at all, or returned no signing info for it —
         *  distinct from [SignatureMismatch]: there was no certificate to compare in the first place. */
        data class Unparsable(val reason: String) : Result()
    }

    /**
     * Runs both checks (whichever the caller has a claim for) against [apkFile]. [claimedSha256]
     * and [claimedSigningFingerprint] are each optional and independent — pass `null` for a claim
     * the source didn't supply, rather than an empty string.
     */
    fun verify(
        ctx: Context,
        apkFile: File,
        claimedSha256: String?,
        claimedSigningFingerprint: String?
    ): Result {
        val checked = mutableSetOf<Check>()
        val missing = mutableSetOf<Check>()

        if (claimedSha256 != null) {
            val actualHex = try {
                sha256Hex(apkFile)
            } catch (e: Exception) {
                return Result.Unreadable(e.message ?: "could not read downloaded file")
            }
            val expectedNorm = normalizeHex(claimedSha256)
            val actualNorm = normalizeHex(actualHex)
            if (expectedNorm != actualNorm) {
                return Result.ChecksumMismatch(expected = expectedNorm, actual = actualNorm)
            }
            checked += Check.CHECKSUM
        } else {
            missing += Check.CHECKSUM
        }

        if (claimedSigningFingerprint != null) {
            val fingerprints = try {
                signingCertSha256Fingerprints(ctx, apkFile)
            } catch (e: Exception) {
                return Result.Unparsable(e.message ?: "could not parse archive signing info")
            } ?: return Result.Unparsable("PackageManager returned no signing info for this archive")

            val expectedNorm = normalizeHex(claimedSigningFingerprint)
            val actualNorm = fingerprints.map { normalizeHex(it) }
            if (expectedNorm !in actualNorm) {
                return Result.SignatureMismatch(expected = expectedNorm, actual = actualNorm)
            }
            checked += Check.SIGNING_FINGERPRINT
        } else {
            missing += Check.SIGNING_FINGERPRINT
        }

        return when {
            missing.isEmpty() -> Result.Trusted(checked)
            checked.isEmpty() -> Result.NothingToVerify(missing)
            else -> Result.PartiallyVerified(checked, missing)
        }
    }

    /** Streams [file] in fixed-size chunks rather than reading it fully into memory — APKs here
     *  routinely run tens of megabytes, and this runs on the same download-completion path that
     *  already avoids loading the whole file where it can (`AppData.kt`'s `DownloadManager` usage). */
    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    /**
     * SHA-256 fingerprint of each of [apkFile]'s *current* signing certificates — the same bytes
     * that would actually be trusted if this archive were installed, not a rotation lineage.
     *
     * Split by API level, same reasoning already on record for `ZealotTrust.kt`'s Ed25519 choice:
     * `PackageManager.GET_SIGNING_CERTIFICATES` / `PackageInfo.signingInfo` were only added at API
     * 28, while this module's `minSdk` is 26 — silently falling through to nothing on API 26/27
     * would make this check a no-op on part of the install base, which is unacceptable for a trust
     * check specifically (same standard `1.a.ii.zi`'s Done note already applied to Ed25519). Below
     * API 28, this falls back to the deprecated `GET_SIGNATURES`/`PackageInfo.signatures`, which is
     * all that API range has; suppressed deliberately, not overlooked.
     *
     * For a multi-signer archive (`SigningInfo.hasMultipleSigners()`), `apkContentsSigners` holds
     * every current signer and a match against *any one* of them is treated as a fingerprint match
     * here — this function reports "does the claimed fingerprint identify one of this archive's
     * current signers", not "is this archive validly signed" (that full check, including requiring
     * *all* multi-signers or a valid rotation lineage, is `PackageManager`'s job at actual install
     * time, not this function's). **Flagged forward, out of this leaf's scope:** past certificates
     * in a rotated signer's lineage (`signingCertificateHistory`) are not consulted — only the
     * current signer(s) are, since that's what a freshly downloaded archive's claimed fingerprint
     * should be describing.
     */
    private fun signingCertSha256Fingerprints(ctx: Context, apkFile: File): List<String>? {
        val pm = ctx.packageManager
        val path = apkFile.absolutePath

        val signatures: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
            val signingInfo = info.signingInfo ?: return null
            signingInfo.apkContentsSigners ?: return null
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES) ?: return null
            @Suppress("DEPRECATION")
            info.signatures ?: return null
        }

        if (signatures.isEmpty()) return null

        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { signature ->
            digest.reset()
            digest.digest(signature.toByteArray()).joinToString(separator = "") { "%02x".format(it) }
        }
    }

    /** Claims arrive in whatever case/separator convention the source used (colon-separated,
     *  `sha256:`-prefixed, uppercase, ...) — normalize both sides to bare lowercase hex before
     *  comparing so a formatting difference is never mistaken for a real mismatch. */
    private fun normalizeHex(raw: String): String =
        raw.trim()
            .removePrefix("sha256:").removePrefix("SHA256:").removePrefix("Sha256:")
            .replace(":", "")
            .replace(" ", "")
            .lowercase()
}
