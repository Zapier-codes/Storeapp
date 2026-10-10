package com.vythera.vyxelapps

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The Play Store listing concepts our catalogue model does not carry, kept out of
 * [GitHubRepo] so the shared model stays shaped like the sources that fill it.
 *
 * Everything here is either derived from real fields we already have (badges,
 * similar apps, trailer links) or supplied by an optional per-app `listing.json`
 * on the CDN ([AppListingMeta]). Nothing is fabricated: a panel that has no data
 * hides or explains itself rather than inventing a rating.
 *
 * See `docs/PLAY-PARITY.md` for the full cross-check against Play Store and Play
 * Console, and for where each missing capability can be ported from.
 */

// ─────────────────────────────────────────────────────────────────────────────
// BADGES
// ─────────────────────────────────────────────────────────────────────────────
/**
 * A short label on a listing, as the Play Store shows ("Updated", "Editors'
 * Choice", …). Only the ones we can derive honestly are produced by
 * [badgeFlags]; the rest come from [AppListingMeta.badges].
 */
enum class ListingBadge(val label: String) {
    OPEN_SOURCE("Open source"),
    UPDATED("Recently updated"),
    TRENDING("Trending"),
    EDITORS_CHOICE("Editors' Choice"),
    NO_ADS("No ads"),
    IN_APP_PURCHASES("In-app purchases"),
    PRE_REGISTER("Pre-register"),
}

// ─────────────────────────────────────────────────────────────────────────────
// LISTING META (optional, CDN-supplied)
// ─────────────────────────────────────────────────────────────────────────────
data class ReviewItem(
    val author      : String = "",
    val rating      : Int    = 0,      // 1..5
    val body        : String = "",
    val helpful     : Int    = 0,
    val version     : String = "",
    val date        : String = "",
    val devReply    : String? = null,
)

/**
 * What an app collects and shares, in the Play Data Safety shape.
 *
 * Read from a live install's manifest when the app is on the device
 * ([readInstalledPermissions]); otherwise from `listing.json` when the publisher
 * supplies it. A publisher that supplies neither gets the explanatory empty
 * state, not a guessed panel.
 */
data class DataSafetyInfo(
    val collects       : List<String> = emptyList(),
    val shares         : List<String> = emptyList(),
    val encryptedInTransit : Boolean = false,
    val deletable      : Boolean = false,
    val permissions    : List<String> = emptyList(),
)

data class AppListingMeta(
    val rating        : Float = 0f,
    val ratingCount   : Long = 0,
    val installs      : String = "",
    val contentRating : String = "",
    val developer     : String = "",
    val trailerUrl    : String? = null,
    val badges        : List<ListingBadge> = emptyList(),
    val dataSafety    : DataSafetyInfo? = null,
    val reviews       : List<ReviewItem> = emptyList(),
)

// ─────────────────────────────────────────────────────────────────────────────
// DERIVATION (real fields only)
// ─────────────────────────────────────────────────────────────────────────────
private fun daysSince(iso: String): Long? = try {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    val d = fmt.parse(iso.take(19)) ?: return null
    (System.currentTimeMillis() - d.time) / 86_400_000L
} catch (_: Exception) { null }

/**
 * The badges a listing has earned, from fields we already hold plus any the CDN
 * supplied. Order is the display order: the strongest signal first.
 */
fun badgesFor(repo: GitHubRepo, meta: AppListingMeta? = null): List<ListingBadge> {
    val out = mutableListOf<ListingBadge>()
    meta?.badges?.let { out += it }
    val age = daysSince(repo.updated_at)
    if (age != null && age <= 30) out += ListingBadge.UPDATED
    if (repo.stargazers_count >= 10_000) out += ListingBadge.TRENDING
    // Every source this store aggregates is an open-source catalogue, so the flag
    // is a source fact rather than a per-app one — but it still belongs on the tile
    // next to the Play badges, because it is the one thing a Play user cannot see.
    if (repo.source == null || repo.source == AppSource.GITHUB || repo.source == AppSource.FDROID ||
        repo.source == AppSource.IZZY || repo.source == AppSource.GITLAB ||
        repo.source == AppSource.CODEBERG || repo.source == AppSource.ZEALOT) {
        out += ListingBadge.OPEN_SOURCE
    }
    return out.distinct()
}

/**
 * "You might also like" — scored, not random.
 *
 * Language match is the strongest signal, then shared description words, then a
 * same-source nudge. Only non-zero scores are returned, so an app with no relation
 * to anything shows nothing rather than an arbitrary rail. The scoring runs on
 * cached in-memory strings; no network and no package queries.
 */
fun similarAppsFor(
    target : GitHubRepo,
    pool   : List<GitHubRepo>,
    max    : Int = 8,
): List<GitHubRepo> {
    val words = { s: String ->
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
    }
    val targetWords = words("${target.name} ${target.description.orEmpty()}")
    return pool.asSequence()
        .filter { it.id != target.id && it.iconUrlOrNull != null }
        .map { r ->
            var score = 0
            if (target.language != null && r.language == target.language) score += 3
            score += (targetWords intersect words("${r.name} ${r.description.orEmpty()}")).size
            if (r.source == target.source) score += 1
            r to score
        }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<GitHubRepo, Int>> { it.second }
            .thenByDescending { it.first.stargazers_count })
        .map { it.first }
        .take(max)
        .toList()
}

/**
 * The first playable trailer link in a listing, if the publisher put one in the
 * README or description. Real detection, not a placeholder: no link, no player.
 */
fun trailerUrlFor(repo: GitHubRepo, readme: String?): String? {
    val haystack = (repo.description.orEmpty() + "\n" + (readme ?: ""))
    val yt = Regex("""https?://(?:www\.)?(?:youtube\.com/watch\?v=|youtu\.be/)([A-Za-z0-9_-]{6,})""")
        .find(haystack)?.value
    if (yt != null) return yt
    return Regex("""https?://(?:www\.)?vimeo\.com/\d+""").find(haystack)?.value
}

/** A YouTube id, when the trailer is on YouTube — the app opens it via an Intent. */
fun youTubeId(url: String?): String? {
    if (url == null) return null
    return Regex("""(?:youtube\.com/watch\?v=|youtu\.be/)([A-Za-z0-9_-]{6,})""")
        .find(url)?.groupValues?.getOrNull(1)
}

/** Human-readable install count, from the star count when the CDN supplies none. */
fun installsLabelFor(repo: GitHubRepo, meta: AppListingMeta?): String {
    if (!meta?.installs.isNullOrBlank()) return meta!!.installs!!
    val stars = repo.stargazers_count
    return if (stars > 0) "$stars stars" else ""
}

/**
 * The permissions a live install requests, from PackageManager.
 *
 * This is the honest half of the Data Safety panel: a publisher can claim
 * anything, but the permissions are what the APK on the device actually asks for.
 */
fun readInstalledPermissions(context: android.content.Context, packageName: String): List<String> {
    if (packageName.isBlank()) return emptyList()
    return try {
        val pi = context.packageManager.getPackageInfo(
            packageName, android.content.pm.PackageManager.GET_PERMISSIONS
        )
        (pi.requestedPermissions ?: emptyArray())
            .map { it.substringAfterLast('.') }
            .distinct()
            .sorted()
    } catch (_: Exception) { emptyList() }
}

// ─────────────────────────────────────────────────────────────────────────────
// SEARCH SORT & FILTERS
// ─────────────────────────────────────────────────────────────────────────────
/** The sort keys the Play search results offer, minus the ones we have no data for. */
enum class SearchSort(val label: String) {
    RELEVANCE("Relevance"),
    STARS("Stars"),
    UPDATED("Recently updated"),
    NAME("Name"),
    SIZE("Size"),
}

/** Catalogue filters layered on top of the platform/source chips already present. */
data class SearchFilters(
    val installedOnly : Boolean = false,
    val hasApkOnly    : Boolean = false,
    val minStars      : Int     = 0,
)

fun applySearchView(
    apps    : List<GitHubRepo>,
    sort    : SearchSort,
    filters : SearchFilters,
    installed: Set<Long>,
): List<GitHubRepo> {
    val filtered = apps.filter { r ->
        (!filters.installedOnly || r.id in installed) &&
        (!filters.hasApkOnly || r.apkUrl.isNotBlank() || r.apkSize > 0) &&
        r.stargazers_count >= filters.minStars
    }
    return when (sort) {
        SearchSort.RELEVANCE -> filtered
        SearchSort.STARS     -> filtered.sortedByDescending { it.stargazers_count }
        SearchSort.UPDATED   -> filtered.sortedByDescending { it.updated_at }
        SearchSort.NAME      -> filtered.sortedBy { it.displayName.lowercase() }
        SearchSort.SIZE      -> filtered.sortedByDescending { it.apkSize }
    }
}
