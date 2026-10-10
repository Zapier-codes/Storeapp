package com.vythera.vyxelapps.expressive.data

import com.vythera.vyxelapps.AppSource
import com.vythera.vyxelapps.GitHubRepo
import com.vythera.vyxelapps.InstallHistoryEntry
import com.vythera.vyxelapps.RepoOwner
import com.vythera.vyxelapps.iconUrlOrNull
import com.vythera.vyxelapps.UpdateInfo
import com.vythera.vyxelapps.expressive.data.model.AppItem
import com.vythera.vyxelapps.expressive.data.model.SourceId
import com.vythera.vyxelapps.api.DStoreApp
import com.vythera.vyxelapps.expressive.data.source.epochMillisToIso
import com.vythera.vyxelapps.expressive.data.source.isoToEpochMillis
import com.vythera.vyxelapps.updater.AppScanResult
import com.vythera.vyxelapps.updater.UpdaterSource

/**
 * Adapters between the Classic data model and the Expressive one.
 *
 * These exist so the two shells can be one app rather than two. Classic owns the
 * catalog, search and update engines (`AppViewModel`); Expressive renders the same
 * state through its own components. Previously each shell had its own repository and
 * its own scan logic, which is why they behaved like separate apps — different
 * results, different update lists, and two sets of network traffic.
 */

/** Classic's source enum -> Expressive's. */
fun AppSource?.toSourceId(): SourceId = when (this) {
    AppSource.FDROID -> SourceId.FDroid
    AppSource.IZZY -> SourceId.IzzyOnDroid
    AppSource.GITLAB -> SourceId.GitLab
    AppSource.CODEBERG -> SourceId.Codeberg
    AppSource.FLATHUB -> SourceId.Flathub
    AppSource.WINGET -> SourceId.WinGet
    AppSource.APTOIDE -> SourceId.Aptoide
    // j.vii.b: our own signed store. Classic already loads it (`zealotApps`), so the two
    // shells land on the same `SourceId` for the same app.
    AppSource.ZEALOT -> SourceId.Zealot
    // j.vii.c: Classic's D-Store value (its browse view and installed-list records).
    AppSource.DSTORE -> SourceId.DStore
    // Classic collapses the four module repositories into one source value; the
    // Alt Repo is the safest landing spot because it is the one whose entries
    // carry a real zip url without a further lookup.
    AppSource.MODULE -> SourceId.MagiskAlt
    else -> SourceId.GitHub
}

/**
 * Renders a Classic catalog entry through Expressive's components.
 *
 * `packageId` is preferred over `full_name` for the package name because for
 * IzzyOnDroid entries `full_name` is `owner/repo`, not an Android package id.
 */
fun GitHubRepo.toAppItem(): AppItem {
    val mappedSource = source.toSourceId()
    val pkg = packageId.takeIf { it.isNotBlank() }
    return AppItem(
        id = "${mappedSource.name}:${pkg ?: full_name.ifBlank { name }}",
        source = mappedSource,
        name = name,
        summary = description.orEmpty().take(240),
        description = description.orEmpty(),
        iconUrl = iconUrlOrNull,
        packageName = pkg,
        version = cdnVersion.takeIf { it.isNotBlank() },
        author = owner.login.takeIf { it.isNotBlank() },
        categories = listOfNotNull(language?.takeIf { it.isNotBlank() }),
        stars = stargazers_count,
        // Forks and the last-update date are two of the five inputs to the Vyxel
        // Trust Score. Dropping them here meant a round trip through this bridge
        // silently zeroed them, and the same app scored up to 45 points lower in
        // Expressive than in Classic — an unknown value being scored as a bad one.
        forks = forks_count,
        updatedAt = updated_at.isoToEpochMillis(),
        downloadUrl = apkUrl.takeIf { it.isNotBlank() },
        website = html_url.takeIf { it.isNotBlank() },
        sourceCodeUrl = sourceCodeUrl.takeIf { it.isNotBlank() } ?: html_url,
        sizeBytes = apkSize,
        // A repo with no APK resolved yet still needs its release looked up.
        needsReleaseLookup = apkUrl.isBlank() && mappedSource.isRepoHost,
    )
}

/**
 * Renders one multi-source scan hit as an Expressive card.
 *
 * The scan engine reports where the update came from as its own [UpdaterSource], and
 * the download is a [ScanLink] rather than a plain URL, so the actual fetch stays
 * with Classic's `downloadFromScanResult`.
 */
fun AppScanResult.toAppItem(): AppItem = AppItem(
    id = "scan:$packageName:$newVersion",
    source = scanSourceToSourceId(source),
    name = appName.ifBlank { packageName },
    // An entry the scan found but that is already current has no "→ newer" to show,
    // so it reads as the installed version alone, the way Classic renders it.
    summary = if (hasUpdate) "$currentVersion  →  $newVersion"
    else "$currentVersion  ·  ${source.name}",
    description = whatsNew,
    iconUrl = iconUrl.takeIf { it.isNotBlank() },
    packageName = packageName,
    version = newVersion,
    changelog = whatsNew.takeIf { it.isNotBlank() },
)


/** Expressive's source enum -> Classic's. */
fun SourceId.toAppSource(): AppSource = when (this) {
    SourceId.FDroid -> AppSource.FDROID
    SourceId.IzzyOnDroid -> AppSource.IZZY
    SourceId.GitLab -> AppSource.GITLAB
    SourceId.Codeberg -> AppSource.CODEBERG
    SourceId.Flathub -> AppSource.FLATHUB
    SourceId.WinGet -> AppSource.WINGET
    // j.vii.b: back to Classic's own source value so an install of a Zealot card routes
    // through the same gateway and record as Classic's own Zealot entries.
    SourceId.Zealot -> AppSource.ZEALOT
    // j.vii.c: Classic's own D-Store value. Browse-only, so this only matters for the
    // badge and for any id/route that keys on the source.
    SourceId.DStore -> AppSource.DSTORE
    SourceId.Aptoide, SourceId.ApkPure -> AppSource.APTOIDE
    SourceId.MagiskAlt, SourceId.Googlers,
    SourceId.XposedRepo, SourceId.MagiskLegacy -> AppSource.MODULE
    else -> AppSource.GITHUB
}

/**
 * Turns an Expressive card back into the repo shape Classic's engines expect.
 *
 * Release history, version selection and installation all live on `AppViewModel`,
 * keyed by [GitHubRepo]. Rather than grow a second release fetcher for Expressive —
 * with its own bugs and its own idea of which APK to pick — the detail screen converts
 * back and drives the engine Classic already uses.
 *
 * The id is derived from the item id with the same per-source offsets
 * `AppEntry.toGitHubRepo` applies, so the two shells land on the same
 * `installStates` entry for the same app and a release list fetched by one is
 * immediately available to the other.
 */
fun AppItem.toGitHubRepo(): GitHubRepo {
    val classicSource = source.toAppSource()
    val key = packageName?.takeIf { it.isNotBlank() }
        ?: id.substringAfter(':', "").ifBlank { name }

    /**
     * GitHub's own numeric repo id, when the catalog entry carries one.
     *
     * The CDN index has no Android package name for GitHub repos, so it puts the
     * numeric GitHub id in that field instead. Classic keys `installStates` and its
     * whole release cache by exactly that number, because it is what the GitHub API
     * returns as `id`. Expressive was hashing it into a synthetic key like every
     * other source — so the two shells sat on different entries for the same app,
     * `fetchRelease` resolved into a slot Expressive never read, and a GitHub app
     * could show "No APK" here while Classic had its release the whole time.
     *
     * Reusing the number verbatim is what puts both shells on Classic's resolver.
     */
    val githubRepoId = packageName
        ?.takeIf { classicSource == AppSource.GITHUB }
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
    /**
     * j.vii.b: a Zealot card carries its id in Classic's `ZEALOT_ID_OFFSET` bucket, so the
     * two shells key `installStates`, the release cache and `StoreUpdateChecker` on the same
     * number for the same app. Reused verbatim, the same way GitHub's numeric id is.
     */
    val zealotRepoId = id.substringAfter(':', "")
        .toLongOrNull()
        ?.takeIf { source == SourceId.Zealot && it >= com.vythera.vyxelapps.api.ZEALOT_ID_OFFSET }
    /**
     * j.vii.c: a D-Store card carries its id in Classic's `DSTORE_ID_OFFSET` bucket (the same
     * `dstoreRepoId(slug)` number Classic's own D-Store rows use), reused verbatim so the two
     * shells key `installStates` and the release cache on the same entry.
     */
    val dstoreRepoId = id.substringAfter(':', "")
        .toLongOrNull()
        ?.takeIf { source == SourceId.DStore && it >= com.vythera.vyxelapps.api.DSTORE_ID_OFFSET }
    val offset = when (source) {
        SourceId.GitLab -> 9_000_000_000L
        SourceId.Codeberg -> 8_000_000_000L
        SourceId.FDroid -> 7_000_000_000L
        SourceId.Flathub -> 6_000_000_000L
        SourceId.WinGet -> 5_000_000_000L
        SourceId.IzzyOnDroid -> 4_000_000_000L
        // j.vii.b: Zealot is `AppSource.ZEALOT`, which Classic keys `installStates` and its
        // release cache on by `ZEALOT_ID_OFFSET` (its own bucket). A synthesised id that never
        // lands there would put the two shells on different entries for the same app, so a
        // Zealot card carries Classic's real id from the source and never reaches this branch.
        // Own band kept anyway so an id synthesised from something malformed can't collide.
        SourceId.Zealot -> 30_000_000_000L
        // j.vii.c: above `DSTORE_ID_OFFSET`, so even a synthesised D-Store id stays in D-Store's
        // bucket and `isDStoreRepoId` still recognises it. A real card never reaches here.
        SourceId.DStore -> 21_000_000_000L
        // Own bands so a synthesised id can never land on a real GitHub repo id,
        // which is the one source whose ids are used verbatim.
        SourceId.Aptoide, SourceId.ApkPure -> 3_000_000_000L
        SourceId.MagiskAlt, SourceId.Googlers,
        SourceId.XposedRepo, SourceId.MagiskLegacy -> 2_000_000_000L
        SourceId.Aurora -> 1_500_000_000L
        else -> 0L
    }
    val sourceKey = when (source) {
        SourceId.FDroid -> "fdroid"
        SourceId.IzzyOnDroid -> "izzy"
        SourceId.GitLab -> "gitlab"
        SourceId.Codeberg -> "codeberg"
        SourceId.Flathub -> "flathub"
        SourceId.WinGet -> "winget"
        SourceId.Zealot -> "zealot"
        SourceId.DStore -> "dstore"
        SourceId.Aptoide -> "aptoide"
        SourceId.ApkPure -> "apkpure"
        SourceId.Aurora -> "aurora"
        SourceId.MagiskAlt -> "magiskalt"
        SourceId.Googlers -> "googlers"
        SourceId.XposedRepo -> "xposed"
        SourceId.MagiskLegacy -> "magisklegacy"
        else -> "github"
    }
    /**
     * "owner" and "repo" as the host itself spells them, taken from the source URL.
     *
     * Classic's release lookup calls `getReleases(repo.owner.login, repo.name)` and
     * builds its CDN cache path the same way — it works because its own GitHubRepo
     * came from the API with a real owner. Expressive synthesises the object from a
     * catalog entry, and CDN entries for repo hosts carry no author, so `owner.login`
     * was empty and every lookup went out as `getReleases("", "rustdesk")` — a
     * guaranteed 404, which is why GitHub apps sat on "No APK" no matter how many
     * releases they published. The display name is no substitute either: it is the
     * catalog's label, which is often title-cased or renamed.
     */
    val urlParts = sourceCodeUrl
        ?.substringBefore('?')
        ?.trimEnd('/')
        ?.split('/')
        ?.takeLast(2)
        ?.takeIf { parts -> parts.size == 2 && parts.all { it.isNotBlank() } }
    val repoOwner = urlParts?.get(0) ?: author.orEmpty()
    val repoSlug = urlParts?.get(1) ?: name

    return GitHubRepo(
        id = zealotRepoId ?: dstoreRepoId ?: githubRepoId
            ?: (kotlin.math.abs("$sourceKey:$key".hashCode()).toLong() + offset),
        // The host's own repo name, not the catalog's display label.
        name = if (source.isRepoHost) repoSlug else name,
        // GitHub/GitLab/Codeberg release lookups are keyed by "owner/repo", which for
        // these entries is carried in the source URL rather than the package name.
        full_name = when (source) {
            SourceId.FDroid, SourceId.IzzyOnDroid -> key
            else -> sourceCodeUrl?.let { url ->
                url.trimEnd('/').split('/').takeLast(2).joinToString("/")
                    .takeIf { it.contains('/') }
            } ?: key
        },
        description = description.ifBlank { summary }.takeIf { it.isNotBlank() },
        stargazers_count = stars,
        // Both feed the trust score, which is computed from this object. See the note
        // on the reverse mapping: without them Expressive scores every repo as having
        // no forks and no update in living memory.
        forks_count = forks,
        updated_at = epochMillisToIso(updatedAt),
        html_url = website ?: sourceCodeUrl.orEmpty(),
        owner = RepoOwner(login = repoOwner, avatar_url = iconUrl.orEmpty()),
        source = classicSource,
        apkUrl = downloadUrl.orEmpty(),
        cdnVersion = version.orEmpty(),
        sourceCodeUrl = sourceCodeUrl.orEmpty(),
        // A numeric GitHub id is not a package name — passing it through made the
        // detail page print "Package 111583593" and made every installed-app match
        // fail, since no package is ever called that.
        packageId = if (githubRepoId != null) "" else packageName.orEmpty(),
        // Aptoide, the CDN and the module repos all report a size in their search
        // response. Classic has no release asset to read one from for these, so
        // without this the detail page prints "0 B".
        apkSize = sizeBytes,
        // j.vii.a: a claim the source published travels with the card, so Classic's engines
        // (InstallGateway) check it too when the detail screen drives the install.
        claimedSha256 = claimedSha256,
        claimedSigningFingerprint = claimedSigningFingerprint,
        // The first category, which for a module is its family — Magisk, Zygisk,
        // LSPosed or KernelSU. `language` is the only free-text field on GitHubRepo
        // that nothing else needs, and Classic's module screen filters on it.
        language = categories.firstOrNull(),
    )
}

/**
 * One row of the "from all sources" scan list, carrying whether the entry is actually
 * out of date.
 *
 * A scan reports on *every* installed app it can match, and most of them are current
 * — `hasUpdate` is false far more often than not. [AppItem] has no field for that, so
 * dropping to it alone loses the distinction. Expressive used to render only
 * `hasUpdate` entries, which meant a scan that matched three apps and found all three
 * current displayed nothing at all and looked like a dead button.
 */
data class ScanRow(val item: AppItem, val hasUpdate: Boolean)

fun AppScanResult.toScanRow(): ScanRow = ScanRow(toAppItem(), hasUpdate)

/**
 * Renders a D-Store catalog row as an Expressive card — leaf j.vii.c.
 *
 * Pure, so a JVM test can drive it. Browse-only: `downloadUrl` is deliberately left
 * `null`, because D-Store's `/api/catalog` is unsigned and a row carries nothing to
 * verify a download against (operator decision 5a/5b). `installAction` therefore reads
 * Unavailable, and the card can be opened and read but never installed.
 *
 * The id is the same `dstoreRepoId(slug)` bucket Classic's own D-Store rows use, so a
 * card here and one in Classic are the same entry to anything keying on the id.
 */
fun DStoreApp.toAppItem(): AppItem {
    val displayName = name.trim().ifEmpty { slug }
    val pkg = package_name.trim().takeIf { it.isNotEmpty() }
    return AppItem(
        id = "${SourceId.DStore.name}:${com.vythera.vyxelapps.api.dstoreRepoId(slug)}",
        source = SourceId.DStore,
        name = displayName,
        summary = summary.trim(),
        description = summary.trim(),
        iconUrl = icon?.takeIf { it.startsWith("https://") },
        packageName = pkg,
        version = version.trim().takeIf { it.isNotBlank() },
        updatedAt = updated_at.isoToEpochMillis(),
        author = developer_name.trim().takeIf { it.isNotBlank() },
        license = license.trim().takeIf { it.isNotBlank() },
        categories = listOfNotNull(category.trim().takeIf { it.isNotBlank() }),
        // Browse-only: no URL, so the card is never installable.
        downloadUrl = null,
        sizeBytes = (size_mb * 1024 * 1024).toLong(),
    )
}


/**
 * Renders a tracked-release update as an Expressive card.
 *
 * [UpdateInfo] carries no package name — it is keyed by repo — so the repo name is
 * used as the identity and the install is routed back through Classic.
 */
fun UpdateInfo.toAppItem(): AppItem = AppItem(
    id = "update:$repoId:$latestTag",
    source = SourceId.GitHub,
    name = repoName.substringAfterLast('/'),
    summary = "$currentTag  →  $latestTag",
    description = changelog,
    version = latestTag,
    changelog = changelog.takeIf { it.isNotBlank() },
    sourceCodeUrl = "https://github.com/$repoName",
)

/**
 * Renders an app the user installed through Vyxel as an Expressive card.
 *
 * Classic's "Installed" list is its install history, which Expressive had no view of
 * at all — the two shells showed different things for the same device.
 */
fun InstallHistoryEntry.toAppItem(): AppItem = AppItem(
    id = "installed:${packageName.ifBlank { repoName }}",
    source = installSourceToSourceId(source),
    name = repoName.substringAfterLast('/'),
    // The package name, not the repo owner.
    //
    // This row used to read out `ownerLogin`, which in an installed-apps list is a
    // stray GitHub username under an app's own name — it tells the reader nothing
    // about what is on their phone. The package id at least identifies the app
    // uniquely, and is what anyone cross-checking against Android's settings needs.
    summary = packageName.ifBlank { ownerLogin },
    iconUrl = iconUrl?.takeIf { it.isNotBlank() },
    packageName = packageName.takeIf { it.isNotBlank() },
    version = tagName.removePrefix("v").takeIf { it.isNotBlank() },
    updatedAt = installedAt,
    author = ownerLogin.takeIf { it.isNotBlank() },
)

/** Install history stores the source as a lowercase string, not the enum. */
private fun installSourceToSourceId(raw: String?): SourceId =
    when (raw?.lowercase()?.replace("-", "")) {
        "fdroid" -> SourceId.FDroid
        "izzy", "izzyondroid" -> SourceId.IzzyOnDroid
        "gitlab" -> SourceId.GitLab
        "codeberg" -> SourceId.Codeberg
        "flathub" -> SourceId.Flathub
        "winget" -> SourceId.WinGet
        // Classic collapses Aptoide and APKPure onto one enum value, so an app
        // installed from either records as "aptoide". Without these the installed
        // list badged every Play app "GitHub" — the same default that once
        // mislabelled the scan results.
        "aptoide", "apkpure" -> SourceId.Aptoide
        "aurora" -> SourceId.Aurora
        "module" -> SourceId.MagiskAlt
        else -> SourceId.GitHub
    }

/** Best-effort mapping of the updater's source label onto a catalog source. */
internal fun scanSourceToSourceId(source: UpdaterSource): SourceId =
    when (source.name.lowercase().replace("-", "").replace("_", "")) {
        "fdroid", "fdroidupdatersource" -> SourceId.FDroid
        "izzy", "izzyupdatersource", "izzyondroid" -> SourceId.IzzyOnDroid
        "gitlab" -> SourceId.GitLab
        "codeberg" -> SourceId.Codeberg
        // The two general mirrors, which are where every Play-only app is found.
        //
        // Missing here, they fell through to the GitHub default and every Pinterest,
        // Reddit and Amazon Music update in the scan was badged as coming from
        // GitHub — naming the wrong provenance for exactly the entries whose
        // provenance the reader most wants to check.
        "aptoide" -> SourceId.Aptoide
        "apkpure" -> SourceId.ApkPure
        // j.vii.d: a store row comes from the shared store check, badged as the store it
        // came from rather than as a repo.
        "zealot", "zealotupdatersource" -> SourceId.Zealot
        "dstore", "dstoreupdatersource" -> SourceId.DStore
        else -> SourceId.GitHub
    }
