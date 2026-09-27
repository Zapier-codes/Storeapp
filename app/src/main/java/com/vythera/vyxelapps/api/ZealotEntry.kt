package com.vythera.vyxelapps.api

import com.google.gson.Gson
import com.vythera.vyxelapps.AppSource
import com.vythera.vyxelapps.GitHubRepo
import com.vythera.vyxelapps.RepoOwner

/**
 * Zealot signed-catalog-index entry shape — leaf `1.a.ii.zo`. Native port of the fields
 * D-Store's `lib/sources/zealot.ts` (`RawApp`/`RawVersion`) actually reads off Zealot's v2
 * catalog index, same "only the fields this reader actually reads" posture that file's own
 * header comment states — not the full `catalog_index_v2.schema.json` shape (data-safety,
 * editorial, sponsored-slots, collections, available-regions have no matching field on
 * `GitHubRepo` today, so they're left out here the same way `AppEntry`, `GitLabProject`,
 * `CodebergRepo`, and `FlathubApp` each only model what their own converter needs). Named
 * `ZealotEntry` rather than `RawApp`, matching this file's sibling converters' own naming
 * convention (`GitLabProject`, `CodebergRepo`, `FlathubApp` — named after the source, not
 * prefixed "Raw").
 *
 * These types are read-only input to `toUnifiedRepo()` below; nothing here does any trust
 * checking of its own — that's `1.a.ii.zi` (`ZealotTrust.kt`), a separate, earlier step. Never
 * parse untrusted index text with `parseZealotEntries` below; only ever
 * `ZealotClient.resolveVerifiedIndex()`'s already-signature-verified output.
 */

/**
 * The next free billion-scale bucket above GitLab's existing `9_000_000_000L`, so a Zealot
 * `GitHubRepo.id` can never collide with any of the other six sources' synthetic ids. Shared
 * (not just inlined in `toUnifiedRepo()` below) so any other code that needs to recognize a
 * Zealot-sourced id from a bare `Long` — e.g. update-check routing, which has no dedicated
 * `source` field to read on `InstallHistoryEntry` — uses the exact same value rather than a
 * second copy of this magic number that could silently drift from this one.
 */
const val ZEALOT_ID_OFFSET = 10_000_000_000L

data class ZealotCompatibility(val min_sdk: Int? = null)

data class ZealotVersion(
    val version_name        : String?             = null,
    val download_url        : String?             = null,
    val sha256              : String?             = null,
    val size_bytes          : Long?                = null,
    val signing_fingerprint : String?             = null,
    val changelog           : String?             = null,
    val compatibility       : ZealotCompatibility = ZealotCompatibility()
)

data class ZealotPublisher(
    val name        : String  = "",
    val profile_url : String? = null,
    val verified    : Boolean? = null
)

data class ZealotIcon(val url: String? = null)

data class ZealotListing(
    val title                  : String     = "",
    val description            : String?    = null,
    val icon                   : ZealotIcon = ZealotIcon(),
    val content_rating         : String?    = null,
    val contains_ads           : Boolean?   = null,
    val has_in_app_purchases   : Boolean?   = null
)

data class ZealotLinks(
    val site   : String? = null,
    val source : String? = null,
    val tracker: String? = null,
    val donate : String? = null
)

data class ZealotEntry(
    val id           : String         = "",
    val package_name : String?        = null,
    val publisher    : ZealotPublisher = ZealotPublisher(),
    val listing      : ZealotListing  = ZealotListing(),
    val slug         : String         = "",
    val summary      : String?        = null,
    val category     : String?        = null,
    val license      : String?        = null,
    val links        : ZealotLinks    = ZealotLinks(),
    val created_at   : String         = "",
    val updated_at   : String         = "",
    /** Newest first — matches Zealot's own documented `App#catalog_releases` order, so `versions.firstOrNull()` (never a re-sort here) is always the latest, same assumption `zealot.ts` makes. */
    val versions     : List<ZealotVersion> = emptyList()
)

/** The index's top-level `apps` array — only the one field this leaf needs; `schema_version`/`sequence`/`expires_at` are `1.a.ii.zi`'s `IndexEnvelope`'s job, not re-declared here. */
data class ZealotIndex(val apps: List<ZealotEntry> = emptyList())

/**
 * Parses an already-verified `index.json` text (`ZealotClient.resolveVerifiedIndex()`'s output)
 * into entries. This function does no trust checking of its own — verification and parsing are
 * deliberately separate steps (`1.a.ii.zi` vs. this leaf) — so it must never be called on raw,
 * unverified fetch output. Malformed/unparsable JSON collapses to an empty list, same "never
 * throw, empty is a valid state" posture every other CDN source in this app already uses.
 */
fun parseZealotEntries(verifiedIndexJsonText: String): List<ZealotEntry> =
    try {
        Gson().fromJson(verifiedIndexJsonText, ZealotIndex::class.java)?.apps ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

/**
 * Converter from one verified Zealot index entry into this app's unified `GitHubRepo` shape,
 * same pattern as the other six sources' converters (`GitLabProject.toUnifiedRepo()`,
 * `CodebergRepo.toUnifiedRepo()`, `FlathubApp.toUnifiedRepo()`, `AppEntry.toGitHubRepo()`).
 *
 * Field-mapping notes (decisions this leaf makes, not arbitrary picks):
 *  - `id`: numeric-string id + a dedicated `10_000_000_000L` offset — the next free
 *    billion-scale bucket above GitLab's existing `9_000_000_000L`, so a Zealot id can never
 *    collide with any of the other six sources' synthetic `GitHubRepo.id` values.
 *  - `owner.avatar_url` holds the *app's own icon*, not a real per-developer avatar — same
 *    convention `FlathubApp.toUnifiedRepo()` and `AppEntry.toGitHubRepo()` already establish
 *    for every source that has no per-owner avatar concept of its own.
 *  - `stargazers_count`/`forks_count` are `0` — Zealot's index has no popularity metric, and
 *    `1.a.iii.zi`'s prepend-first merge (still open) is explicitly *not* a `stargazers_count`
 *    boost, so this converter doesn't invent one either.
 *  - `html_url` (the "View on Zealot" link's target): falls back through `links.site` then
 *    `links.source`, since Zealot's index carries no ready-made storefront listing URL of its
 *    own today — that would be a per-tenant D-Store URL, which needs `1.c.ii.zi`'s
 *    `TenantConfig` (still open) to resolve. **Flagged forward, not silently decided:** once a
 *    tenant's storefront base URL is available at runtime, this should prefer
 *    `"$storefrontBase/apps/${slug}"` over these link fallbacks.
 */
fun ZealotEntry.toUnifiedRepo(): GitHubRepo {
    val latest = versions.firstOrNull()

    val repoId = id.toLongOrNull()?.plus(ZEALOT_ID_OFFSET)
        ?: (kotlin.math.abs(id.hashCode()).toLong() + ZEALOT_ID_OFFSET)

    val displayName = listing.title.ifBlank { slug }
    val fullName     = package_name?.takeIf { it.isNotBlank() } ?: slug

    val description = listing.description?.trim()?.ifEmpty { null }
        ?: summary?.trim()?.ifEmpty { null }

    val viewUrl = links.site?.takeIf { it.isNotBlank() }
        ?: links.source?.takeIf { it.isNotBlank() }
        ?: ""

    return GitHubRepo(
        id               = repoId,
        name             = displayName,
        full_name        = fullName,
        description      = description,
        stargazers_count = 0,
        forks_count      = 0,
        html_url         = viewUrl,
        owner            = RepoOwner(
            login      = publisher.name.ifBlank { "zealot" },
            avatar_url = listing.icon.url ?: ""
        ),
        language         = null,
        updated_at       = updated_at,
        source           = AppSource.ZEALOT,
        apkUrl           = latest?.download_url ?: "",
        cdnVersion       = latest?.version_name ?: ""
    )
}
