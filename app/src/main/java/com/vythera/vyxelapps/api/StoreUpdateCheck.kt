package com.vythera.vyxelapps.api

import com.vythera.vyxelapps.AppSource
import com.vythera.vyxelapps.GitHubRepo
import com.vythera.vyxelapps.InstallHistoryEntry
import com.vythera.vyxelapps.Release
import com.vythera.vyxelapps.ReleaseAsset
import com.vythera.vyxelapps.RepoOwner
import com.vythera.vyxelapps.ZealotClient
import kotlinx.coroutines.CancellationException
import android.content.Context

/**
 * Track h, leaf `h.iv`: the one store-first update check every caller shares.
 *
 * `AppViewModel.checkForUpdatesNow()`, `AppViewModel.updateAll()` and `UpdateCheckWorker` all ask
 * this object instead of each keeping their own copy of the routing. Before this leaf the in-app
 * check and `updateAll` looked an installed app up on GitHub by owner/repo — which could never be
 * right for an app published on our own stores — and the worker had a Zealot-only branch with no
 * D-Store branch. Here:
 *
 *  - an app whose `GitHubRepo.id` is in Zealot's bucket ([isZealotRepoId]) is looked up in Zealot's
 *    signed index, through the same verified fetch the catalog uses ([ZealotClient.resolveVerifiedIndex],
 *    which checks signature, schema, freshness and anti-rollback). Its entry is signature-checked, so
 *    its `download_url`, `sha256` and `signing_fingerprint` are real claims and the update is
 *    **installable**;
 *  - an app in D-Store's bucket ([isDStoreRepoId]) is looked up in D-Store's public catalog API
 *    ([DStoreCatalogClient], unsigned). Its update is **shown but never installed**: nothing about a
 *    D-Store row is verified, so it carries no checksum or fingerprint to install against — the
 *    operator's decision 5a/5b and leaf `7.b.ii.zi`, not settled here. This leaf only stops the wrong
 *    GitHub lookup and shows the store's version;
 *  - an app on neither store is not this object's business: the callers keep the GitHub
 *    `getLatestRelease(owner, repo)` path for the six GitHub-type sources, which the additive-only
 *    decision leaves untouched;
 *  - a store app that has no matching (or no verifiable) store entry this round is skipped, never
 *    falling through to a GitHub lookup that could not be right for it — same rule the worker already
 *    applied to Zealot.
 *
 * The Zealot index is fetched at most once per check, and only when an installed entry is actually in
 * that bucket, so a device with no store installs never pays for the extra network call (the same
 * laziness the worker had before).
 *
 * What this is NOT: it does not download, install or verify a file (that is the install path, and for
 * a Zealot claim [GitHubRepo.claimedSha256]/[claimedSigningFingerprint] are carried through so
 * `InstallGateway`/`Verifier` still check the bytes). It does not read `rollout`, so a staged ramp is
 * treated as a full release, matching `SelfUpdatePlanner` and D-Store's recorded choice to skip
 * staged-rollout consumption (a no-account store has no stable device identity).
 */

/** True for an id in either store's bucket (Zealot or D-Store). Those are checked against the store's own index, never GitHub. */
fun isStoreRepoId(id: Long): Boolean = isZealotRepoId(id) || isDStoreRepoId(id)

/**
 * One update a store check found, in the shape the install path needs.
 *
 * [installable] is true only for a Zealot (signed-index) entry: it has a verified [apkUrl] and the two
 * claims below. A D-Store entry is shown ([latestTag]) with `installable = false` and no claims.
 */
data class StoreUpdate(
    val repoId: Long,
    val repoName: String,
    val currentTag: String,
    val latestTag: String,
    val changelog: String,
    val apkUrl: String,
    val claimedSha256: String?,
    val claimedSigningFingerprint: String?,
    val installable: Boolean
)

/**
 * The repo to hand the install path, for an installable (Zealot) update. Carries the signed index's
 * claims so `InstallGateway`/`Verifier` check the downloaded bytes, exactly as `fetchRelease()`'s
 * Zealot path already does. `source = ZEALOT` is what `downloadAndInstall` reads to allow the install.
 */
fun StoreUpdate.toRepo(): GitHubRepo = GitHubRepo(
    id                        = repoId,
    name                      = repoName,
    full_name                 = repoName,
    owner                     = RepoOwner(),
    source                    = AppSource.ZEALOT,
    apkUrl                    = apkUrl,
    cdnVersion                = latestTag,
    claimedSha256             = claimedSha256,
    claimedSigningFingerprint = claimedSigningFingerprint
)

/** The synthetic release + APK asset for an installable update, mirroring `fetchRelease()`'s store shape. */
fun StoreUpdate.toRelease(): Release = Release(
    tag_name = latestTag,
    name     = latestTag,
    assets   = listOf(
        ReleaseAsset(
            name                 = "$repoName-$latestTag.apk",
            browser_download_url = apkUrl,
            size                 = 0L,
            content_type         = "application/vnd.android.package-archive"
        )
    ),
    body = changelog
)

/**
 * Checks a set of installed entries against the stores. One instance per check (the Zealot index is
 * cached for its lifetime). Never throws: a failure for one entry is that entry having no update.
 */
class StoreUpdateChecker(context: Context) {

    private val appContext = context.applicationContext

    /** Zealot's verified entries by `GitHubRepo.id`, fetched once. `null` until the first Zealot entry needs it. */
    private var zealotByRepoId: Map<Long, GitHubRepo>? = null

    private suspend fun zealotRepo(repoId: Long): GitHubRepo? {
        var map = zealotByRepoId
        if (map == null) {
            val text = ZealotClient.resolveVerifiedIndex(appContext)
            map = text
                ?.let { parseZealotEntries(it).map { entry -> entry.toUnifiedRepo() } }
                ?.associateBy { it.id }
                ?: emptyMap()
            zealotByRepoId = map
        }
        return map[repoId]
    }

    /**
     * The D-Store entry for an installed app, or `null`.
     *
     * D-Store's API searches `name`/`summary` only (no package-name lookup), so this asks for the
     * installed package name as the needle and keeps only exact `package_name` matches — which is
     * why a D-Store entry's `packageName` must have been recorded at install time. The stored
     * `repoName` (the slug, `dstoreRepoId`'s key) picks between several exact matches; the first is
     * used only when the slug is absent. An app whose name does not contain its package name is not
     * found this round — the documented "no update this round" outcome, not a wrong lookup.
     */
    private suspend fun dstoreRepo(entry: InstallHistoryEntry): GitHubRepo? {
        val pkg = entry.packageName.trim()
        if (pkg.isEmpty()) return null
        val page = DStoreCatalogClient.page(query = pkg, limit = DStoreCatalogClient.PAGE_MAX) ?: return null
        val matches = page.apps.filter { it.package_name == pkg }
        if (matches.isEmpty()) return null
        val app = matches.firstOrNull { it.slug == entry.repoName.trim() } ?: matches.first()
        return app.toUnifiedRepo()
    }

    /** The store entry to compare against, or `null` when the app is on no store or the store has nothing. */
    private suspend fun storeRepo(entry: InstallHistoryEntry): GitHubRepo? =
        when {
            isZealotRepoId(entry.repoId) -> zealotRepo(entry.repoId)
            isDStoreRepoId(entry.repoId) -> dstoreRepo(entry)
            else -> null
        }

    /**
     * One update per entry whose store version differs from the installed tag. [entries] is the
     * caller's already de-duplicated install history (one per repo). Never throws.
     */
    suspend fun check(entries: List<InstallHistoryEntry>): List<StoreUpdate> {
        val found = ArrayList<StoreUpdate>()
        for (entry in entries) {
            if (!isStoreRepoId(entry.repoId)) continue
            try {
                val repo = storeRepo(entry) ?: continue
                val latest = repo.cdnVersion.trim()
                if (latest.isEmpty() || latest == entry.tagName) continue
                val installable = repo.source == AppSource.ZEALOT
                found.add(
                    StoreUpdate(
                        repoId                    = entry.repoId,
                        repoName                  = entry.repoName,
                        currentTag                = entry.tagName,
                        latestTag                 = latest,
                        changelog                 = "",
                        apkUrl                    = if (installable) repo.apkUrl else "",
                        claimedSha256             = if (installable) repo.claimedSha256 else null,
                        claimedSigningFingerprint = if (installable) repo.claimedSigningFingerprint else null,
                        installable               = installable
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // no update for this entry this round
            }
        }
        return found.distinctBy { it.repoId }
    }
}
