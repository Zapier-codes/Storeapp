package com.vythera.vyxelapps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val requestNotifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* system handles grant/deny state; no further action needed */ }

    override fun attachBaseContext(newBase: Context) {
        val prefs    = newBase.getSharedPreferences("vyxel_prefs", Context.MODE_PRIVATE)
        val langCode = prefs.getString("user_language_code", "en") ?: "en"
        val locale   = Locale.forLanguageTag(langCode)
        Locale.setDefault(locale)
        val config   = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    /**
     * Full-bleed: the status bar is hidden so hero art and the premium skins'
     * backgrounds run to the top of the screen. Swiping from the edge brings the bars
     * back transiently, which is what users expect — hiding them with no way back
     * would take the clock and battery with them.
     */
    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * The system restores the bars whenever the window loses and regains focus — after
     * a permission dialog, the recents switcher, or an install prompt. Re-applying on
     * focus is what makes the choice stick instead of surviving only until the first
     * interruption.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        hideStatusBar()
        // Without this the punch-hole/notch strip stays a black bar once the status
        // bar is hidden: the window is laid out below the cutout, so the background
        // simply is not painted up there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        setContent {
            // Single observable source of truth; seeded from disk so the correct
            // shell renders on the first frame instead of flashing Classic.
            val uiStyleState = UiStylePrefs.state(this@MainActivity)
            val uiStyle = uiStyleState.value

            if (uiStyle == UiStyle.Expressive) {
                val storeViewModel: com.vythera.vyxelapps.expressive.ui.StoreViewModel = viewModel()
                // Same AppViewModel instance the Classic shell uses, so search and
                // update state survive switching skins.
                val sharedViewModel: AppViewModel = viewModel()
                val expressiveSettings by storeViewModel.settings.collectAsStateWithLifecycle()
                // Language comes from the shared Classic settings, so picking one in
                // either shell switches both. Expressive reads shared vocabulary from
                // LocalStrings and its own copy from LocalExpressiveStrings.
                val language = sharedViewModel.state.settings.language
                // j.v: every skin is free, so the stored skin renders as-is. (The old
                // licence gate and the frame it took to reset a locked skin are gone.)
                val skin = expressiveSettings.skin
                CompositionLocalProvider(
                    LocalStrings provides stringsForLanguage(language),
                    com.vythera.vyxelapps.expressive.ui.LocalExpressiveStrings provides
                        com.vythera.vyxelapps.expressive.ui.expressiveStringsFor(language),
                ) {
                    com.vythera.vyxelapps.expressive.ui.theme.VyxelTheme(
                        themeMode = expressiveSettings.themeMode,
                        dynamicColor = expressiveSettings.dynamicColor,
                        motionIntensity = expressiveSettings.motionIntensity,
                        skin = skin,
                    ) {
                        com.vythera.vyxelapps.expressive.ui.ExpressiveShell(
                            viewModel = storeViewModel,
                            appViewModel = sharedViewModel,
                            onSwitchToClassic = {
                                UiStylePrefs.set(this@MainActivity, UiStyle.Classic)
                            },
                        )
                    }
                }
                return@setContent
            }

            // Hoist the ViewModel to the top so its init fires immediately,
            // starting loadAll() while the splash is still playing.
            val appViewModel: AppViewModel = viewModel()
            val settings = appViewModel.state.settings

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (!granted) requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // First run shows the brand splash, then straight to Home. The old
            // first-run GitHub-token page is gone: the token is an optional
            // GitHub-search setting, not a gate, and Home works without it.
            var showSplash by remember { mutableStateOf(true) }
            val screen = if (showSplash) "SPLASH" else "HOME"

            Surface(color = Color.Black) {
                Crossfade(
                    targetState   = screen,
                    animationSpec = tween(durationMillis = 500),
                    label         = "nav"
                ) { s ->
                    when (s) {
                        "SPLASH" -> AppstoreSplash(
                            onFinished = {
                                if (!settings.hasSeenOnboarding) {
                                    appViewModel.updateSettings(
                                        settings.copy(hasSeenOnboarding = true)
                                    )
                                }
                                showSplash = false
                            }
                        )
                        else -> HomeScreen(viewModel = appViewModel)
                    }
                }
            }
        }
    }
}