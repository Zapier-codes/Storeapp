package com.vythera.vyxelapps

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/**
 * The Play Store-style home surfaces, 2026 design language.
 *
 * The Play Store's home has moved to a full-bleed hero carousel over a bold
 * colour wash, "continue/quick-access" cards, full-bleed collection tiles with
 * the image behind the label, and generous 8dp-grid spacing. This file adds the
 * three surfaces that carry that look, kept separate from `AppComponents.kt` so
 * the Classic widgets stay readable:
 *
 *  - [PlayHeroCarousel]  — full-bleed, edge-to-edge feature pager with a
 *    gradient scrim so the title stays legible over any artwork.
 *  - [PlayCollectionCard] — full-bleed tile, image behind the label.
 *  - [PlayListRow]        — the flat, borderless one-per-line list the store uses
 *    for "Recommended for you".
 */

// ─────────────────────────────────────────────────────────────────────────────
// HERO CAROUSEL — full-bleed pager with a legibility scrim
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PlayHeroCarousel(
    apps       : List<GitHubRepo>,
    seed       : Int = 0,
    onAppClick : (GitHubRepo) -> Unit
) {
    if (apps.isEmpty()) return
    val context = LocalContext.current
    val pages   = remember(apps, seed) { apps.shuffled(kotlin.random.Random(seed.toLong())).take(6) }
    val pager   = rememberPagerState(pageCount = { pages.size })

    // Slow auto-advance, same rhythm as the store's rotating hero.
    LaunchedEffect(pages.size) {
        if (pages.size <= 1) return@LaunchedEffect
        while (true) {
            delay(6000)
            pager.animateScrollToPage(
                (pager.currentPage + 1) % pages.size,
                animationSpec = tween(700, easing = FastOutSlowInEasing)
            )
        }
    }

    Column {
        HorizontalPager(
            state    = pager,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val repo = pages[page]
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(0.dp))   // full-bleed: no side inset
                    .clickable { onAppClick(repo) }
            ) {
                AsyncImage(
                    model = remember(repo.iconUrlOrNull) {
                        ImageRequest.Builder(context)
                            .data(repo.iconUrlOrNull)
                            .crossfade(400)
                            .build()
                    },
                    contentDescription = repo.displayName,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                    error              = null,
                    placeholder        = null
                )
                // No artwork: a colour wash derived from the id keeps the hero from
                // going flat black.
                if (repo.iconUrlOrNull == null) {
                    val wash = heroWashFor(repo.id.toInt())
                    Box(Modifier.fillMaxSize().background(wash))
                }
                // Scrim: bottom-heavy so the title reads over any image, fading to
                // transparent so the top of the art stays visible.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f    to Color.Transparent,
                                0.45f to Color.Black.copy(alpha = 0.35f),
                                1f    to Color.Black.copy(alpha = 0.82f)
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Text(
                        "FEATURED",
                        color         = Color.White.copy(alpha = 0.85f),
                        fontSize      = 11.sp,
                        fontWeight    = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        repo.displayName,
                        color      = Color.White,
                        fontSize   = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    if (!repo.description.isNullOrEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            repo.description,
                            color    = Color.White.copy(alpha = 0.78f),
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        // Page dots, centred just under the hero.
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            repeat(pages.size) { i ->
                val active = pager.currentPage == i
                val w by animateFloatAsState(if (active) 22f else 8f, tween(250), label = "dotW")
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = w.dp, height = 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// COLLECTION TILE — full-bleed, image behind the label
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PlayCollectionCard(
    title      : String,
    subtitle   : String,
    emoji      : String,
    apps       : List<GitHubRepo>,
    onClick    : () -> Unit,
    modifier   : Modifier = Modifier
) {
    val context = LocalContext.current
    val cover   = remember(apps) { apps.firstOrNull { it.iconUrlOrNull != null } }
    val seedId  = remember(title) { title.hashCode() }
    Box(
        modifier = modifier
            .width(300.dp)
            .height(170.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable { onClick() }
    ) {
        if (cover?.iconUrlOrNull != null) {
            AsyncImage(
                model = remember(cover.iconUrlOrNull) {
                    ImageRequest.Builder(context)
                        .data(cover.iconUrlOrNull)
                        .crossfade(300)
                        .build()
                },
                contentDescription = title,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize().background(heroWashFor(seedId)))
            // No artwork: a large emoji carries the tile, as the store's own
            // category art is a colour wash with one focal mark.
            Text(
                emoji,
                fontSize = 54.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // Diagonal scrim: the label sits bottom-left, so darken that corner most.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.80f))
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        ) {
            Text(
                title,
                color      = Color.White,
                fontSize   = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                if (subtitle.isNotBlank()) subtitle else "${apps.size} apps",
                color    = Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FLAT LIST ROW — one app per line, no card chrome
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun PlayListRow(
    repo        : GitHubRepo,
    isInstalled : Boolean = false,
    onClick     : () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = remember(repo.iconUrlOrNull) {
                ImageRequest.Builder(context)
                    .data(repo.iconUrlOrNull)
                    .crossfade(250)
                    .build()
            },
            contentDescription = null,
            contentScale       = ContentScale.Crop,
            modifier           = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            error = null
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                repo.displayName,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onSurface,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                repo.description?.takeIf { it.isNotBlank() } ?: "@${repo.owner.login}",
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (repo.stargazers_count > 0) {
                    Icon(Icons.Rounded.Star, null, tint = StarGold, modifier = Modifier.size(12.dp))
                    Text(
                        formatStars(repo.stargazers_count),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isInstalled) {
                    Text(
                        "Installed",
                        style      = MaterialTheme.typography.labelSmall,
                        color      = GreenOk,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        // The store's flat rows end in a pill, not a chevron.
        FilledTonalButton(
            onClick  = onClick,
            shape    = CircleShape,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(if (isInstalled) "Open" else "Get", fontWeight = FontWeight.Bold)
        }
    }
}

/** "Recommended for you" as a flat, borderless list — the store's 2026 list style. */
@Composable
fun PlayFlatList(
    title       : String,
    apps        : List<GitHubRepo>,
    installed   : Set<Long>,
    onAppClick  : (GitHubRepo) -> Unit,
    max         : Int = 5
) {
    if (apps.isEmpty()) return
    Column(modifier = Modifier.padding(top = 18.dp)) {
        Text(
            title,
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color      = MaterialTheme.colorScheme.onSurface,
            modifier   = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        apps.take(max).forEach { repo ->
            PlayListRow(
                repo        = repo,
                isInstalled = installed.contains(repo.id),
                onClick     = { onAppClick(repo) }
            )
        }
    }
}

/** The collections rail as full-bleed Play-style tiles. */
@Composable
fun PlayCollectionsRail(
    collections: List<AppCollection>,
    onClick    : (AppCollection) -> Unit
) {
    LazyRow(
        contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(collections.size) { i ->
            val c = collections[i]
            // The per-collection app lists are fetched only when a tile is opened,
            // so the rail has no cover art to show — the emoji-on-wash tile is the
            // intended look, not a fallback.
            PlayCollectionCard(
                title    = c.title,
                subtitle = c.subtitle,
                emoji    = c.emoji,
                apps     = emptyList(),
                onClick  = { onClick(c) }
            )
        }
    }
}

/**
 * A soft two-stop gradient seeded from a stable id, used wherever a tile has no
 * artwork — the Play Store's own category art is a colour wash, so a missing icon
 * still reads as an intentional tile rather than a hole.
 */
private fun heroWashFor(seed: Int): Brush {
    val hues = listOf(
        Color(0xFF1A73E8) to Color(0xFF0B57D0),
        Color(0xFFD93025) to Color(0xFFB31412),
        Color(0xFF188038) to Color(0xFF0D652D),
        Color(0xFF9334E6) to Color(0xFF7627BB),
        Color(0xFFE8710A) to Color(0xFFC25A00),
        Color(0xFF00838F) to Color(0xFF005662),
    )
    val (a, b) = hues[((seed % hues.size) + hues.size) % hues.size]
    return Brush.linearGradient(listOf(a, b))
}
