package com.vythera.vyxelapps.expressive.data.source

import android.content.Context
import com.vythera.vyxelapps.ZealotClient
import com.vythera.vyxelapps.api.ZealotEntry
import com.vythera.vyxelapps.api.parseZealotEntries
import com.vythera.vyxelapps.expressive.data.model.AppItem
import com.vythera.vyxelapps.expressive.data.model.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Our own signed store, as an Expressive source — leaf `j.vii.b`.
 *
 * Classic already carries Zealot's apps; the Expressive shell did not. This is the
 * missing half: it reads the same Ed25519-signed catalog index through the same
 * verified fetch Classic uses ([ZealotClient.resolveVerifiedIndex], which owns its own
 * fetch, signature check, anti-rollback and last-known-good fallback — this source
 * never touches raw index text), and maps each entry's newest version to an [AppItem].
 *
 * Two things make this the highest-trust source in the list, not just another mirror:
 *  - the index text is signature-verified before anything is mapped, so every field
 *    here — including the package name — is a claim the store itself signed;
 *  - it is the only source that publishes a checksum and a signing fingerprint, so it
 *    is also the only one that populates [AppItem.claimedSha256] and
 *    [AppItem.claimedSigningFingerprint]. That is what gives `j.vii.a`'s verify step
 *    something to check on an Expressive install.
 *
 * The id is the entry's own id in Classic's `ZEALOT_ID_OFFSET` bucket, so an app here and
 * the same app in Classic are one entry to the engines that key on `GitHubRepo.id`.
 */
class ZealotSource(context: Context) : AppSource {

    private val appContext = context.applicationContext

    override val id: SourceId = SourceId.Zealot

    /** The whole index, mapped. One request, and a fresh app is promotion-worthy. */
    override suspend fun featured(): List<AppItem> = all()

    /** Zealot's index is small and already in memory after a home load, so search is local. */
    override suspend fun search(query: String): List<AppItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        return all().filter {
            relevanceScore(it.name, it.packageName, it.summary, it.description, it.categories, q) > 0
        }
    }

    private suspend fun all(): List<AppItem> = withContext(Dispatchers.IO) {
        runCatching {
            ZealotClient.resolveVerifiedIndex(appContext)
                ?.let { parseZealotEntries(it) }
                ?.mapNotNull { it.toAppItem() }
                .orEmpty()
        }.getOrDefault(emptyList())
    }
}

/**
 * One verified Zealot entry as an Expressive card.
 *
 * Pure and public so a JVM test can drive it with a hand-built [ZealotEntry] and prove
 * the claim fields land — no Android, no network. `null` when the entry has no usable
 * identity (neither a package name nor a slug), since without one it could not dedupe,
 * install or be looked up again.
 *
 * Only the newest `available` version is offered. A `halted` or `pulled` release is the
 * publisher taking it back, so it is skipped rather than handed to someone as an update;
 * if the newest version is withdrawn and an older one is still live, the older one is
 * offered, which is the correct answer to "what can I install from here right now".
 */
fun ZealotEntry.toAppItem(): AppItem? {
    val pkg = package_name?.takeIf { it.isNotBlank() }
    val slug = slug.takeIf { it.isNotBlank() } ?: id.takeIf { it.isNotBlank() }
    if (pkg == null && slug == null) return null

    val version = versions.firstOrNull { it.status == null || it.status == "available" }

    val numericId = id.toLongOrNull()
    val stableId = numericId?.plus(com.vythera.vyxelapps.api.ZEALOT_ID_OFFSET)
        ?: (kotlin.math.abs((slug ?: pkg ?: id).hashCode()).toLong() +
            com.vythera.vyxelapps.api.ZEALOT_ID_OFFSET)

    return AppItem(
        // A stable id in Zealot's own bucket, so a card here and the same app in Classic
        // are one entry to anything that keys on the numeric id.
        id = "${SourceId.Zealot.name}:$stableId",
        source = SourceId.Zealot,
        name = listing.title.ifBlank { slug ?: pkg.orEmpty() },
        summary = (summary?.trim()?.ifEmpty { null }
            ?: listing.description?.trim()?.take(240)?.ifEmpty { null }).orEmpty(),
        description = (listing.description?.trim()?.ifEmpty { null }
            ?: summary?.trim().orEmpty()).orEmpty(),
        iconUrl = listing.icon.url?.takeIf { it.isNotBlank() },
        packageName = pkg,
        version = version?.version_name?.takeIf { it.isNotBlank() },
        // The index publishes version_code as a string; a malformed one is "unknown", not 0.
        versionCode = version?.version_code?.toLongOrNull() ?: 0L,
        updatedAt = updated_at.isoToEpochMillis(),
        author = publisher.name.takeIf { it.isNotBlank() },
        license = license?.takeIf { it.isNotBlank() },
        categories = listOfNotNull(category?.takeIf { it.isNotBlank() }),
        downloadUrl = version?.download_url?.takeIf { it.startsWith("https://") },
        sizeBytes = version?.size_bytes ?: 0L,
        website = links.site?.takeIf { it.isNotBlank() },
        sourceCodeUrl = links.source?.takeIf { it.isNotBlank() },
        donateUrl = links.donate?.takeIf { it.isNotBlank() },
        changelog = version?.changelog?.takeIf { it.isNotBlank() },
        // The only source that publishes these. They are what j.vii.a's verify step checks.
        claimedSha256 = version?.sha256?.takeIf { it.isNotBlank() },
        claimedSigningFingerprint = version?.signing_fingerprint?.takeIf { it.isNotBlank() },
    )
}
