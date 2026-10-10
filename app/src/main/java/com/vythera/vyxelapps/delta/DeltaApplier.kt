package com.vythera.vyxelapps.delta

import com.vythera.vyxelapps.delta.DeltaPatchInfo
import java.security.MessageDigest

/**
 * The update-path glue around [FileByFile]: pick the delta whose base matches what is installed, run the
 * optional checksum gates, and hand back the reconstructed APK. Pure Kotlin (no Android APIs) so the
 * selection and verification rules are unit-testable off-device; the caller owns the network fetch, reading
 * the installed base APK's bytes, and the fallback to a full download.
 *
 * Owner **S** on the parity kanban (card Z-P13 "**S** apply"). The decision this class is careful about is
 * *when to refuse*: a delta is never applied on a guess. If no patch names the installed version, or a
 * recorded checksum does not match, the caller must download the full APK — a wrong-but-successful-looking
 * patch would be worse than a larger download, and the full file is always correct.
 */
object DeltaApplier {

    class ApplyError(message: String) : Exception(message)

    /**
     * The patch to update from [installedVersionCode], or `null` when the index has none for it.
     *
     * Matched on the exact version code the index published (`from_version_code`, Zealot's `build_version`)
     * after trimming: the base bytes the generator diffed were that exact release's APK, so a near-miss
     * (a normalised "1.2" for a published "1.2.0") would apply against the wrong base. An exact match or
     * nothing -- the full download is the fallback. Zealot's own endpoint (`Download::ReleasesController#
     * delta_manifest_for`) does the same exact-first lookup.
     */
    fun selectPatch(patches: List<DeltaPatchInfo>?, installedVersionCode: String): DeltaPatchInfo? {
        if (patches.isNullOrEmpty()) return null
        val wanted = installedVersionCode.trim()
        if (wanted.isEmpty()) return null
        return patches.firstOrNull { it.fromVersionCode.trim() == wanted }
    }

    /**
     * Reconstruct the new APK from [installedBase] and [patchBytes].
     *
     * @param expectedPatchSha256 `patch.sha256`; when non-null the patch bytes must hash to it or nothing is
     *   applied (a corrupt or swapped patch never reaches the decoder).
     * @param expectedToSha256 `patch.toSha256`; when non-null the result must hash to it — the byte-for-byte
     *   acceptance step the card asks for.
     * @param expectedFromSha256 `patch.fromSha256`; when non-null the installed base must hash to it, so the
     *   patch is applied only against the exact APK it was built from.
     * @throws ApplyError on any mismatch or on a malformed patch.
     */
    fun applyPatch(
        installedBase: ByteArray,
        patchBytes: ByteArray,
        expectedPatchSha256: String? = null,
        expectedFromSha256: String? = null,
        expectedToSha256: String? = null,
    ): ByteArray {
        expectedPatchSha256?.let {
            if (!sha256Hex(patchBytes).equals(norm(it), ignoreCase = true)) {
                throw ApplyError("patch checksum does not match the published sha256")
            }
        }
        expectedFromSha256?.let {
            if (!sha256Hex(installedBase).equals(norm(it), ignoreCase = true)) {
                throw ApplyError("installed APK does not match the patch's from_sha256")
            }
        }
        val result = FileByFile.apply(installedBase, patchBytes)
        expectedToSha256?.let {
            if (!sha256Hex(result).equals(norm(it), ignoreCase = true)) {
                throw ApplyError("patched APK does not match the published to_sha256")
            }
        }
        return result
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun norm(hex: String): String = hex.trim().removePrefix("sha256:").replace(":", "").replace(" ", "")
}
