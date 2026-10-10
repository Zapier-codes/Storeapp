package com.vythera.vyxelapps

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The launch screen: the app icon, then "Appstore" typed out beneath it in a brush
 * calligraphy face, then Home.
 *
 * This replaces the old first-run flow, which showed a page asking for a GitHub
 * personal access token before the store could be used at all. That page is gone:
 * the token is an optional GitHub-search nicety, not a gate, and it is still
 * editable in Settings. So the first thing anyone sees is the brand, not a form.
 *
 * The typewriter is driven off a single counter rather than an animation API so the
 * cadence is exact and the caret can sit at the true end of the text as it grows.
 */
private const val BRAND_NAME = "Appstore"

@Composable
fun AppstoreSplash(onFinished: () -> Unit) {
    var typed by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(300)
        for (i in 1..BRAND_NAME.length) {
            typed = i
            delay(105)
        }
        delay(700)
        onFinished()
    }

    // The caret blinks the whole time; while typing it reads as a cursor, after the
    // last letter as a resting insertion point.
    val caretAlpha by rememberInfiniteTransition(label = "caret").animateFloat(
        initialValue  = 1f,
        targetValue   = 0f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label         = "caretAlpha"
    )

    // Icon eases in a touch after the first frame, so the screen is not already
    // "settled" before anything is drawn.
    val iconScale by animateFloatAsState(
        targetValue   = 1f,
        animationSpec = tween(520),
        label         = "iconScale"
    )

    Box(
        modifier         = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter            = painterResource(R.drawable.splash_icon),
                contentDescription = "Appstore",
                modifier           = Modifier
                    .size(148.dp)
                    .scale(0.88f + 0.12f * iconScale)
                    .alpha(iconScale)
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text       = BRAND_NAME.take(typed),
                    color      = Color.White,
                    fontFamily = FontFamily(Font(R.font.caveat_brush)),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 58.sp,
                    textAlign  = TextAlign.Center
                )
                // Blinks while typing (a cursor) and stops once the word is complete.
                if (typed < BRAND_NAME.length) {
                    Text(
                        text       = "\u258C",
                        color      = Color.White.copy(alpha = caretAlpha),
                        fontFamily = FontFamily(Font(R.font.caveat_brush)),
                        fontWeight = FontWeight.Bold,
                        fontSize   = 58.sp
                    )
                }
            }
        }
    }
}
