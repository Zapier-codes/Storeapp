package com.vythera.vyxelapps.api

import com.vythera.vyxelapps.AppSource
import com.vythera.vyxelapps.GitHubRepo
import com.vythera.vyxelapps.RepoOwner

/**
 * D-Store catalog-API entry shape and converter — leaf `7.b.iii.zi` (first of the four leaves
 * D-Store's `7.b.i.zo` splits into; `7.b.iii.zo` is the client, `7.b.iv.zi`/`7.b.iv.zo` the
 * browse and search wiring). Nothing here fetches, parses a response, or is called from any
 * screen yet.
 *
 * The fields are the ones D-Store's `GET /api/catalog` returns per app (contract version 1,
 * `lib/catalog-api.ts` and `docs/CATALOG-API.md` in D-Store), and only those. **That response is
 * unsigned**: unlike [ZealotEntry], which only ever comes out of `ZealotClient.resolveVerifiedIndex()`,
 * nothing about a [DStoreApp] has been verified by anything. Two consequences are enforced in
 * [toUnifiedRepo] and are the point of this file:
 *  - `claimedSha256` and `claimedSigningFingerprint` are never set (the API has no such field, and
 *    a value would make `InstallGateway`/`Verifier` treat an unverified claim as a checked one);
 *  - `apkUrl` is only ever an `https://` `download_url`.
 *
 * An app here is an Aptoide-origin row of D-Store's table, i.e. an app Zealot's index lacks.
 * Zealot's own apps never come through this path.
 *
 * Fields are nullable only where the contract says `null` (`icon`, `download_url`,
 * `reported_downloads`); the rest default to blank like [ZealotEntry]. Strict parsing (rejecting
 * a bad or incomplete entry instead of defaulting it) is the client's job, `7.b.iii.zo`, not this
 * converter's: a blank `slug` here would give an id shared by every such entry, so the client must
 * drop those before they get here.
 */

/**
 * Start of the D-Store `GitHubRepo.id` bucket. [ZEALOT_ID_OFFSET] is `10_000_000_000L` and a Zealot
 * id adds a numeric id or a 31-bit hash to it, so everything a Zealot id can realistically be sits
 * below `12_200_000_000L`; this starts a full 10 billion above, so the two cannot meet.
 * Anything that tells a Zealot id from another by "at or above [ZEALOT_ID_OFFSET]"
 * (`UpdateCheckWorker`) must use [isZealotRepoId] instead, because a D-Store id is also above it.
 */
const val DSTORE_ID_OFFSET = 20_000_000_000L

/** True for an id in Zealot's bucket only: at or above [ZEALOT_ID_OFFSET] and below [DSTORE_ID_OFFSET]. */
fun isZealotRepoId(id: Long): Boolean = id >= ZEALOT_ID_OFFSET && id < DSTORE_ID_OFFSET

/** True for an id in D-Store's bucket. */
fun isDStoreRepoId(id: Long): Boolean = id >= DSTORE_ID_OFFSET

data class DStoreApp(
    val slug               : String  = "",
    val package_name       : String  = "",
    /** `"aptoide"` today; the API also allows `"zealot"` in its type, but no such row is served. Not read by the converter. */
    val origin             : String  = "",
    val name               : String  = "",
    val summary            : String  = "",
    val icon               : String? = null,
    val version            : String  = "",
    val app_type           : String  = "",
    val category           : String  = "",
    val developer_name     : String  = "",
    val license            : String  = "",
    val size_mb            : Double  = 0.0,
    val download_url       : String? = null,
    /** Aptoide's own reported figure, `null` = not reported. Deliberately not mapped to a popularity field, see [toUnifiedRepo]. */
    val reported_downloads : Long?   = null,
    val updated_at         : String  = ""
)

/**
 * A stable `GitHubRepo.id` for a D-Store `slug`: [DSTORE_ID_OFFSET] plus a 61-bit FNV-1a hash of
 * the slug's UTF-8 bytes. Stable across runs and devices (no `hashCode()`, whose 32 bits would
 * collide among tens of thousands of apps; at 61 bits a collision among a million is about one
 * in a million). The slug is the table's unique key, so the id follows the app, not its position.
 */
fun dstoreRepoId(slug: String): Long {
    var hash = -3750763034362895579L // FNV-1a 64-bit offset basis (0xcbf29ce484222325), as a signed Long
    for (b in slug.toByteArray(Charsets.UTF_8)) {
        hash = (hash xor (b.toLong() and 0xffL)) * 1099511628211L // FNV prime
    }
    return DSTORE_ID_OFFSET + (hash and 0x1FFFFFFFFFFFFFFFL)
}

private fun String.httpsOrNull(): String? = if (startsWith("https://", ignoreCase = false)) this else null

/**
 * Converter into this app's unified [GitHubRepo], same pattern as [ZealotEntry.toUnifiedRepo] and
 * the other sources' converters. Decisions this leaf makes:
 *  - `id`: [dstoreRepoId] of the slug, its own bucket above Zealot's.
 *  - `source`: [AppSource.DSTORE].
 *  - `packageName`: `package_name` when not blank. It is a real Android package id (Aptoide's), so
 *    [dedupeByPackage] can merge on it, but it is **unverified**, so it must never outrank
 *    Zealot's, which is signature-checked (the ranking itself is `7.b.iv.zi`, not here).
 *  - `apkUrl`: `download_url` only if it starts with `https://`, else blank. This is Aptoide's own
 *    delivery URL, a reference to a file D-Store does not host.
 *  - `claimedSha256`, `claimedSigningFingerprint`: always `null`. Never set these for this source.
 *  - `stargazers_count`, `forks_count`: `0`. `reported_downloads` is Aptoide's own number, not a
 *    star count, and `stargazers_count` is what ranking and "popular" shelves read, so mapping it
 *    would invent popularity in a field that means something else (the same reasoning
 *    [ZealotEntry.toUnifiedRepo] gives for its zeros). How D-Store apps are ordered is `7.b.iv.zi`.
 *  - `html_url`: blank. The API carries no storefront page URL and the storefront's base URL is not
 *    known here; a blank one hides the "View on ..." link rather than inventing a target.
 *  - `owner.avatar_url`: the app's own `https://` icon (the convention every other converter
 *    follows), blank when it is not https.
 *  - `originTenantId`: `null`; this API has no tenants.
 */
fun DStoreApp.toUnifiedRepo(): GitHubRepo {
    val displayName = name.trim().ifEmpty { slug }
    val pkg         = package_name.trim().takeIf { it.isNotEmpty() }

    return GitHubRepo(
        id               = dstoreRepoId(slug),
        name             = displayName,
        full_name        = pkg ?: slug,
        description      = summary.trim().ifEmpty { null },
        stargazers_count = 0,
        forks_count      = 0,
        html_url         = "",
        owner            = RepoOwner(
            login      = developer_name.trim().ifEmpty { "D-Store" },
            avatar_url = icon?.httpsOrNull() ?: ""
        ),
        language         = null,
        updated_at       = updated_at,
        source           = AppSource.DSTORE,
        apkUrl           = download_url?.httpsOrNull() ?: "",
        cdnVersion       = version,
        claimedSha256             = null,
        claimedSigningFingerprint = null,
        packageName               = pkg,
        originTenantId            = null
    )
}
