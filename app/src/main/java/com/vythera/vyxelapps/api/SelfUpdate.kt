package com.vythera.vyxelapps.api

/**
 * Track h, leaf `h.i.zi`: the pure planner that decides whether the store itself has an update to offer.
 *
 * Input is an already signature-verified [ZealotIndex] (never raw fetch output; see `ZealotEntry.kt`), the
 * installed `applicationId`, the installed `versionCode` and the device's `Build.VERSION.SDK_INT`. Nothing here
 * touches the network, the clock, Android or any file, so it runs as a plain unit test.
 *
 * The rules (Zealot's Task 47 design and Storeapp's Track h; the Java updater injected into published apps
 * follows the same ones, so change both together):
 *  - the entry is the one whose `package_name` equals the installed package, compared exactly; if the index
 *    holds MORE than one entry for that package, nothing is offered (a federated index could carry another
 *    tenant's build of the same package, and which one is ours is not this function's call);
 *  - a version is a candidate only when its `status` is `available` and its `version_code` is a whole number
 *    above the installed one (`halted` and `pulled` are never offered);
 *  - a candidate is refused, with a stated reason, unless it has an `https` `download_url`, a `sha256` of 64
 *    hex digits, a `size_bytes` above zero and a non-blank `signing_fingerprint`; a missing field is never
 *    defaulted, so an update with no checksum is never offered;
 *  - a candidate whose `min_sdk` is above the device is refused; a candidate with NO `min_sdk` is accepted (the
 *    installed copy already runs here, and the four fields above are the ones the install checks need);
 *  - among the candidates that pass, the highest `version_code` wins (not the first listed, not the newest
 *    upload), and an older complete version is never offered over a newer one that passes.
 *
 * Not read on purpose: `rollout` (a staged percentage needs a device bucket this app does not have, so a ramp
 * below 100 is not honoured yet; flagged forward to h.i.zo) and the signing certificate itself (that is
 * `h.iii.zi`, which compares it with the installed one before a session starts).
 */

/** What the store would download and install: everything the downloader and installer check, already validated. */
data class SelfUpdateOffer(
    val versionName: String,
    val versionCode: Long,
    val downloadUrl: String,
    /** Lower-case 64-digit hex. */
    val sha256: String,
    val sizeBytes: Long,
    val signingFingerprint: String,
    val minSdk: Int?,
    /** The version's changelog, trimmed; null when blank. The banner shows its first line. */
    val changelog: String?
)

sealed class SelfUpdateDecision {
    data class Offer(val offer: SelfUpdateOffer) : SelfUpdateDecision()

    /** Nothing to offer; [reason] is a plain sentence (for a log or a diagnostic screen, not for the banner). */
    data class NoOffer(val reason: String) : SelfUpdateDecision()
}

/** The offer, or null when there is none. The reason is dropped; keep the [SelfUpdateDecision] when it matters. */
fun SelfUpdateDecision.offerOrNull(): SelfUpdateOffer? = (this as? SelfUpdateDecision.Offer)?.offer

object SelfUpdatePlanner {

    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

    fun plan(
        index: ZealotIndex,
        installedPackage: String,
        installedVersionCode: Long,
        deviceSdk: Int
    ): SelfUpdateDecision {
        if (installedPackage.isBlank()) return SelfUpdateDecision.NoOffer("the installed package name is blank")

        val entries = index.apps.filter { it.package_name == installedPackage }
        if (entries.isEmpty()) return SelfUpdateDecision.NoOffer("the index has no entry for $installedPackage")
        if (entries.size > 1) {
            return SelfUpdateDecision.NoOffer("the index has ${entries.size} entries for $installedPackage, so none is trusted")
        }

        var best: SelfUpdateOffer? = null
        // The refusal of the highest-coded newer candidate that failed, so the reason names the likeliest cause.
        var refusedCode = Long.MIN_VALUE
        var refusedReason: String? = null
        var sawAvailable = false
        var sawUnreadableCode = false

        for (version in entries.first().versions) {
            val status = version.status?.trim()
            if (status != null && status != "available") continue // halted or pulled: never offered, never a reason
            sawAvailable = sawAvailable || status == "available"

            val code = version.version_code?.trim()?.toLongOrNull()
            if (code == null || code < 1L) {
                sawUnreadableCode = true
                continue
            }
            if (code <= installedVersionCode) continue

            val verdict = check(version, code, status, deviceSdk)
            if (verdict.first != null) {
                if (best == null || code > best.versionCode) best = verdict.first
            } else if (code > refusedCode) {
                refusedCode = code
                refusedReason = verdict.second
            }
        }

        best?.let { return SelfUpdateDecision.Offer(it) }
        return SelfUpdateDecision.NoOffer(
            refusedReason
                ?: when {
                    sawUnreadableCode -> "a version has no readable version_code"
                    sawAvailable -> "no available version is newer than $installedVersionCode"
                    else -> "the index lists no available version"
                }
        )
    }

    /** `offer` set when the version passes every rule, else `reason`. Exactly one of the pair is non-null. */
    private fun check(version: ZealotVersion, code: Long, status: String?, deviceSdk: Int): Pair<SelfUpdateOffer?, String?> {
        fun refuse(why: String) = Pair<SelfUpdateOffer?, String?>(null, "version $code is not offered: $why")

        if (status == null) return refuse("it has no status")

        val name = version.version_name?.trim().orEmpty()
        if (name.isEmpty()) return refuse("it has no version_name")

        val url = version.download_url?.trim().orEmpty()
        if (url.isEmpty()) return refuse("it has no download_url")
        if (!url.startsWith("https://", ignoreCase = true) || url.length <= "https://".length) {
            return refuse("its download_url is not an https address")
        }

        val sha = version.sha256?.trim().orEmpty()
        if (sha.isEmpty()) return refuse("it has no sha256")
        if (!SHA256_HEX.matches(sha)) return refuse("its sha256 is not 64 hex digits")

        val size = version.size_bytes
        if (size == null) return refuse("it has no size_bytes")
        if (size <= 0L) return refuse("its size_bytes is not above zero")

        val fingerprint = version.signing_fingerprint?.trim().orEmpty()
        if (fingerprint.isEmpty()) return refuse("it has no signing_fingerprint")

        val minSdk = version.compatibility.min_sdk
        if (minSdk != null && minSdk > deviceSdk) {
            return refuse("it needs Android API $minSdk and this device has $deviceSdk")
        }

        return Pair(
            SelfUpdateOffer(
                versionName = name,
                versionCode = code,
                downloadUrl = url,
                sha256 = sha.lowercase(),
                sizeBytes = size,
                signingFingerprint = fingerprint,
                minSdk = minSdk,
                changelog = version.changelog?.trim()?.ifEmpty { null }
            ),
            null
        )
    }
}
