package com.vythera.vyxelapps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Play-Store parity surfaces (docs/PLAY-PARITY.md).
 *
 * The panels a Play listing has that our catalogue did not, built on real data
 * only: derived badges, a scored "You might also like" rail, per-app auto-update,
 * pre-register, a developer-page link, a Data Safety panel (live permissions when
 * installed, publisher `listing.json` when supplied), a ratings/reviews block, and
 * a "Watch trailer" action when the README carries a real video link.
 *
 * Kept out of `AppComponents.kt` for the same reason `PlayHome.kt` is: the Classic
 * widgets stay readable and these read as one Play-shaped group.
 */

// ─────────────────────────────────────────────────────────────────────────────
// BADGES
// ─────────────────────────────────────────────────────────────────────────────

/** One derived or publisher badge, in the Play listing's pill shape. */
@Composable
fun ListingBadgeChip(badge: ListingBadge) {
    val (bg, fg) = when (badge) {
        ListingBadge.EDITORS_CHOICE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ListingBadge.TRENDING       -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ListingBadge.UPDATED        -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ListingBadge.NO_ADS         -> GreenOk.copy(0.16f) to GreenOk
        ListingBadge.OPEN_SOURCE    -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else                        -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(badge.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

/** A row of badges; renders nothing when there are none. */
@Composable
fun ListingBadgesRow(badges: List<ListingBadge>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        badges.take(4).forEach { ListingBadgeChip(it) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RATING
// ─────────────────────────────────────────────────────────────────────────────

/** A compact metric pill: rating · installs · size · version. */
@Composable
private fun MetricPill(text: String, emphasize: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (emphasize) MaterialTheme.colorScheme.primary.copy(0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium,
            color      = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The Play rating line: "★ 4.3 ★★★★★ (1.2k)" plus the installs metric.
 *
 * A five-glyph meter drawn from text so it needs no extra icon dependency. When
 * the catalogue has no rating the row says so — it never prints a zero-star
 * listing, which reads as a one-star app rather than an unrated one.
 */
@Composable
fun PlayRatingRow(meta: AppListingMeta?, fallbackInstalls: String) {
    val rating = meta?.rating ?: 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rating > 0f && meta != null) {
            val full = rating.roundToInt().coerceIn(0, 5)
            MetricPill("★ " + String.format(java.util.Locale.US, "%.1f", rating), emphasize = true)
            Text("★".repeat(full) + "☆".repeat(5 - full), color = StarGold, style = MaterialTheme.typography.bodySmall)
            if (meta.ratingCount > 0) {
                Text("(${formatStars(meta.ratingCount.toInt())})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            MetricPill("Not yet rated")
        }
        val installs = meta?.installs?.takeIf { it.isNotBlank() } ?: fallbackInstalls
        if (installs.isNotBlank()) MetricPill(installs)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LIFECYCLE (auto-update · pre-register)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Per-app auto-update and pre-register, the two lifecycle switches the Play
 * listing carries. Auto-update is a real, persisted opt-out the update checker
 * honours; pre-register records the intent to reserve the next release.
 */
@Composable
fun PlayLifecycleControls(
    autoUpdateEnabled  : Boolean,
    onToggleAutoUpdate : (Boolean) -> Unit,
    isPreRegistered    : Boolean,
    onTogglePreRegister: () -> Unit,
    showPreRegister    : Boolean,
) {
    GlassCard(shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-update", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (autoUpdateEnabled) "Update this app when a new release lands"
                        else "Skipped by Update all and the background check",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoUpdateEnabled, onCheckedChange = onToggleAutoUpdate)
            }
            if (showPreRegister) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(0.5f))
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.NewReleases, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pre-register", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (isPreRegistered) "You'll be told when the next release ships"
                            else "Reserve the next release of this app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isPreRegistered) {
                        OutlinedButton(onClick = onTogglePreRegister) { Text("Cancel") }
                    } else {
                        Button(onClick = onTogglePreRegister) { Text("Register") }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DATA SAFETY
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Data Safety — what the app collects and shares, and what it can reach.
 *
 * The permission list is read from the APK actually installed on this device when
 * it is present ([readInstalledPermissions]), which is the honest source; the
 * collection/share claims come from the publisher's `listing.json`. With neither
 * we say so rather than showing a reassuring empty panel.
 */
@Composable
fun DataSafetyPanel(meta: AppListingMeta?, livePermissions: List<String>, isInstalled: Boolean) {
    val ds    = meta?.dataSafety
    val perms = if (livePermissions.isNotEmpty()) livePermissions else ds?.permissions.orEmpty()
    SectionHeader("Data safety")
    GlassCard(shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ds == null && perms.isEmpty()) {
                Text(
                    if (isInstalled) "Permissions could not be read for this install."
                    else "The publisher has not filed a Data safety form for this listing. Install it to see the permissions the APK actually requests.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (ds != null) {
                    SafetyRow("Collects", ds.collects)
                    SafetyRow("Shares", ds.shares)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricPill(if (ds.encryptedInTransit) "Encrypted in transit" else "Not encrypted in transit")
                        if (ds.deletable) MetricPill("Data can be deleted")
                    }
                }
                if (perms.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isInstalled) "Permissions requested by the installed app" else "Permissions declared in the listing",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        perms.take(24).forEach { MetricPill(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafetyRow(label: String, items: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(64.dp))
        Text(
            if (items.isEmpty()) "Nothing declared" else items.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REVIEWS
// ─────────────────────────────────────────────────────────────────────────────

/** Ratings and written reviews, from the publisher's `listing.json` when present. */
@Composable
fun ReviewsPanel(meta: AppListingMeta?) {
    val reviews = meta?.reviews.orEmpty().filter { it.rating in 1..5 || it.body.isNotBlank() }
    SectionHeader("Ratings and reviews")
    if (reviews.isEmpty()) {
        GlassCard(shape = MaterialTheme.shapes.extraLarge) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "No reviews yet. This catalogue rates apps by their own trust signals (stars, update cadence, release history) rather than collecting new reviews.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reviews.take(6).forEach { r ->
            GlassCard(shape = MaterialTheme.shapes.extraLarge) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val full = r.rating.coerceIn(0, 5)
                        Text("★".repeat(full) + "☆".repeat(5 - full), color = StarGold, style = MaterialTheme.typography.bodySmall)
                        Text(r.author.ifBlank { "Anonymous" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        if (r.version.isNotBlank()) Text(r.version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (r.body.isNotBlank()) {
                        Text(r.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                    }
                    if (r.helpful > 0) {
                        Text("${r.helpful} found this helpful", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    r.devReply?.takeIf { it.isNotBlank() }?.let { reply ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(10.dp)
                        ) {
                            Column {
                                Text("Developer reply", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Text(reply, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SIMILAR APPS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * "You might also like" — the Play listing's related-apps rail.
 *
 * Fed by [similarAppsFor], which scores by language, shared description words and
 * source. An app with nothing related shows no rail rather than an arbitrary one,
 * which is the whole point of scoring instead of shuffling.
 */
@Composable
fun SimilarAppsRail(apps: List<GitHubRepo>, onAppClick: (GitHubRepo) -> Unit) {
    if (apps.isEmpty()) return
    SectionHeader("You might also like")
    LazyRow(
        contentPadding        = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(apps.size) { i ->
            val r = apps[i]
            Column(
                modifier = Modifier
                    .width(96.dp)
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onAppClick(r) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier         = Modifier
                        .size(64.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    coil.compose.AsyncImage(
                        model              = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(r.iconUrlOrNull)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier           = Modifier.fillMaxSize().clip(MaterialTheme.shapes.large),
                        contentScale       = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    r.displayName,
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color      = MaterialTheme.colorScheme.onSurface,
                    maxLines   = 2,
                    overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign  = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/** Divider used between the appended Play-parity sections inside the detail list. */
@Composable
fun PlaySectionDivider() {
    HorizontalDivider(
        color    = MaterialTheme.colorScheme.outlineVariant.copy(0.4f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// SEARCH SORT + FILTERS
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The Play search results' sort/filter strip: a Sort menu plus the catalogue
 * filters we actually have data for (installed, has an APK, minimum stars).
 *
 * Deliberately does not offer price or content-rating facets: this catalogue has
 * neither, and a facet that never matches anything is worse than no facet.
 */
@Composable
fun SearchSortFilterRow(
    sort     : SearchSort,
    filters  : SearchFilters,
    onSort   : (SearchSort) -> Unit,
    onFilters: (SearchFilters) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    val activeFilters = (if (filters.installedOnly) 1 else 0) +
            (if (filters.hasApkOnly) 1 else 0) +
            (if (filters.minStars > 0) 1 else 0) +
            (if (filters.contentClass != null) 1 else 0) +   // S-P1
            (if (filters.worksOnDevice) 1 else 0)            // S-P2

    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Box {
            AssistChip(
                onClick = { sortMenu = true },
                label   = { Text("Sort: ${sort.label}", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { Icon(Icons.Rounded.Sort, null, modifier = Modifier.size(16.dp)) }
            )
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                SearchSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text        = { Text(s.label) },
                        onClick     = { onSort(s); sortMenu = false },
                        trailingIcon = if (s == sort) {
                            { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }
        Box {
            val label = if (activeFilters == 0) "Filters" else "Filters ($activeFilters)"
            AssistChip(
                onClick = { filterMenu = true },
                label   = { Text(label, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(16.dp)) }
            )
            DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                DropdownMenuItem(
                    text    = { Text("Installed only") },
                    onClick = { onFilters(filters.copy(installedOnly = !filters.installedOnly)) },
                    trailingIcon = if (filters.installedOnly) {
                        { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp)) }
                    } else null
                )
                DropdownMenuItem(
                    text    = { Text("Has installable APK") },
                    onClick = { onFilters(filters.copy(hasApkOnly = !filters.hasApkOnly)) },
                    trailingIcon = if (filters.hasApkOnly) {
                        { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp)) }
                    } else null
                )
                HorizontalDivider()
                Text(
                    "Minimum stars",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp)
                )
                listOf(0, 100, 1_000, 10_000).forEach { n ->
                    DropdownMenuItem(
                        text        = { Text(if (n == 0) "Any" else "${formatStars(n)}+") },
                        onClick     = { onFilters(filters.copy(minStars = n)) },
                        trailingIcon = if (filters.minStars == n) {
                            { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
                HorizontalDivider()
                // S-P2 — "works on your device": drops only apps whose published minSdk the device fails.
                DropdownMenuItem(
                    text    = { Text("Works on this device") },
                    onClick = { onFilters(filters.copy(worksOnDevice = !filters.worksOnDevice)) },
                    trailingIcon = if (filters.worksOnDevice) {
                        { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp)) }
                    } else null
                )
                HorizontalDivider()
                Text(
                    "Content rating",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp)
                )
                // S-P1 — the parental/age filter; unrated apps appear under "Any" only.
                listOf(
                    null            to "Any rating",
                    ContentClass.EVERYONE to "Everyone",
                    ContentClass.TEEN     to "Teen",
                    ContentClass.MATURE   to "Mature",
                    ContentClass.ADULTS   to "Adults only",
                ).forEach { (cls, label) ->
                    DropdownMenuItem(
                        text    = { Text(label) },
                        onClick = { onFilters(filters.copy(contentClass = cls)) },
                        trailingIcon = if (filters.contentClass == cls) {
                            { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
                if (activeFilters > 0) {
                    HorizontalDivider()
                    DropdownMenuItem(
                        text    = { Text("Clear filters", color = MaterialTheme.colorScheme.error) },
                        onClick = { onFilters(SearchFilters()) }
                    )
                }
            }
        }
    }
}
