package com.vythera.vyxelapps.delta

/**
 * Z-P13: one File-by-File update delta as Zealot's signed index publishes it (`delta_patches[]`), carried
 * onto `GitHubRepo.deltaPatches`. It lives here, in the Android-free `delta` package with [DeltaApplier] and
 * [FileByFile], so the selection/applier logic and its tests compile and run off-device. It is deliberately
 * *not* `api/ZealotEntry.kt`'s `ZealotDeltaPatch`: that is the index-entry shape, this is the UI-model shape,
 * and no source other than Zealot should have to import the index types.
 *
 * `fromVersionCode` is the installed build the patch applies from; `downloadUrl` is Zealot's stable endpoint
 * (`GET /download/releases/:id/delta?from=<code>`); the SHA-256s are optional gates the applier runs — the
 * patch itself, the installed base, and the reconstructed APK.
 */
data class DeltaPatchInfo(
    val fromVersionCode: String,
    val downloadUrl: String,
    val size: Long? = null,
    val sha256: String? = null,
    val fromSha256: String? = null,
    val toSha256: String? = null,
    val format: String? = null,
)
