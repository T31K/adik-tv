package com.arflix.tv

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.ProfileRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.navigation.AppNavigation
import com.arflix.tv.navigation.Screen
import com.arflix.tv.ui.components.AppBottomBar
import com.arflix.tv.ui.components.LocalBottomBarInset
import com.arflix.tv.ui.components.currentBottomBarSpec
import com.arflix.tv.ui.components.shouldShowBottomBar
import com.arflix.tv.ui.startup.StartupViewModel
import com.arflix.tv.ui.theme.ArflixTvTheme
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.util.ACCENT_COLOR_KEY
import com.arflix.tv.util.DEVICE_MODE_OVERRIDE_KEY
import com.arflix.tv.util.DeviceType
import com.arflix.tv.util.LocalAppLanguage
import com.arflix.tv.util.LocalDeviceType
import com.arflix.tv.util.LocalHasTouchScreen
import com.arflix.tv.util.OLED_BLACK_BACKGROUND_KEY
import com.arflix.tv.util.SKIP_PROFILE_SELECTION_KEY
import com.arflix.tv.util.detectDeviceType
import com.arflix.tv.util.deviceHasTouchScreen
import com.arflix.tv.util.findActivity
import com.arflix.tv.util.resolveAppLanguage
import com.arflix.tv.util.settingsDataStore
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

private sealed interface ActiveProfileLoadState {
    data object Loading : ActiveProfileLoadState
    data class Loaded(val profile: com.arflix.tv.data.model.Profile?) : ActiveProfileLoadState
}

/**
 * Main Activity - single activity, Compose Navigation.
 * Uses the Android 12+ Splash Screen API for instant launch feedback.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var profileRepository: Lazy<ProfileRepository>

    @Inject
    lateinit var profileManager: Lazy<ProfileManager>

    @Inject
    lateinit var watchlistRepository: Lazy<WatchlistRepository>

    @Inject
    lateinit var mediaRepository: Lazy<MediaRepository>

    // StartupViewModel for parallel loading during splash
    private val startupViewModel: StartupViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        if (!tag.isNullOrEmpty()) {
            val locale = java.util.Locale.forLanguageTag(tag)
            java.util.Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate(). No setKeepOnScreenCondition (black
        // screen on some TVs) — the Compose loading screen takes over immediately.
        installSplashScreen()

        // The splash's postSplashScreenTheme is the mobile theme; on TV use the fullscreen one.
        val initialDeviceType = detectDeviceType(this)
        if (initialDeviceType == DeviceType.TV) {
            setTheme(R.style.Theme_ArflixTV)
        }

        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        // ADIK: request "All files access" so we can read/write the USB drive.
        // Fires on launch until granted (then skipped forever). Guarded so a TV
        // without the settings screen doesn't crash — we just can't reach the drive there.
        if (!com.arflix.tv.megaflix.StoragePermission.hasAllFilesAccess()) {
            runCatching { startActivity(com.arflix.tv.megaflix.StoragePermission.requestIntent(this)) }
        }

        val crashPrefs = getSharedPreferences("arvio_crash_store", Context.MODE_PRIVATE)
        if (crashPrefs.getBoolean("has_pending_crash_report", false)) {
            val crashId = crashPrefs.getString("last_crash_id", "N/A")
            val crashMsg = crashPrefs.getString("last_crash_msg", "Unexpected error")
            val crashTime = crashPrefs.getLong("last_crash_time", System.currentTimeMillis())
            crashPrefs.edit().putBoolean("has_pending_crash_report", false).commit()

            val crashIntent = android.content.Intent(this, com.arflix.tv.ui.screens.crash.CrashReportActivity::class.java).apply {
                putExtra(com.arflix.tv.ui.screens.crash.CrashReportActivity.EXTRA_CRASH_ID, crashId)
                putExtra(com.arflix.tv.ui.screens.crash.CrashReportActivity.EXTRA_CRASH_MSG, crashMsg)
                putExtra(com.arflix.tv.ui.screens.crash.CrashReportActivity.EXTRA_CRASH_TIME, crashTime)
            }
            startActivity(crashIntent)
        }

        requestedOrientation = when (initialDeviceType) {
            DeviceType.TV -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            DeviceType.TABLET -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            DeviceType.PHONE -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }

        if (initialDeviceType == DeviceType.TV) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            )
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        setContent {
            val deviceModeOverride by remember {
                this@MainActivity.settingsDataStore.data.map { it[DEVICE_MODE_OVERRIDE_KEY] }
            }.collectAsStateWithLifecycle(initialValue = null)
            var skipProfileSelection by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) {
                val skipSelection =
                    this@MainActivity.settingsDataStore.data.first()[SKIP_PROFILE_SELECTION_KEY] ?: false
                if (skipSelection) {
                    val profiles = profileRepository.get()
                    val activeProfile = profiles.getActiveProfile()
                    if (activeProfile == null) {
                        val fallbackProfile = profiles.getProfiles().maxByOrNull { it.lastUsedAt }
                            ?: profiles.createDefaultProfileIfNeeded()
                        if (fallbackProfile != null) {
                            profiles.setActiveProfile(fallbackProfile.id)
                        }
                    }
                }
                skipProfileSelection = skipSelection
            }
            val oledBlackBackground by remember {
                this@MainActivity.settingsDataStore.data.map { it[OLED_BLACK_BACKGROUND_KEY] ?: false }
            }.collectAsStateWithLifecycle(initialValue = false)
            val accentColorName by remember {
                this@MainActivity.settingsDataStore.data.map { it[ACCENT_COLOR_KEY] }
            }.collectAsStateWithLifecycle(initialValue = null)
            val activeProfileId by remember {
                profileRepository.get().activeProfileId
            }.collectAsStateWithLifecycle(initialValue = null)
            val initialAppLanguage = remember {
                getSharedPreferences("app_locale", Context.MODE_PRIVATE)
                    .getString("locale_tag", null)?.takeIf { it.isNotBlank() }
                    ?: com.arflix.tv.util.defaultAppLanguage()
            }
            val appLanguage by remember(activeProfileId) {
                this@MainActivity.settingsDataStore.data.map { prefs ->
                    resolveAppLanguage(prefs, activeProfileId)
                }
            }.collectAsStateWithLifecycle(initialValue = initialAppLanguage)
            LaunchedEffect(appLanguage) {
                mediaRepository.get().contentLanguage = appLanguage
            }
            val deviceType = when (deviceModeOverride) {
                "tv" -> DeviceType.TV
                "tablet" -> DeviceType.TABLET
                "phone" -> DeviceType.PHONE
                else -> initialDeviceType
            }
            val hasTouchScreen = remember { deviceHasTouchScreen(this@MainActivity) }
            // No touchscreen → always TV mode (D-pad only devices).
            val effectiveDeviceType = if (!hasTouchScreen && deviceType != DeviceType.TV) DeviceType.TV else deviceType
            // ContextWrapper that only overrides getResources() with localized resources;
            // Hilt still finds the Activity through the wrapper chain.
            val localizedContext = remember(appLanguage) {
                val locale = com.arflix.tv.util.appLocale(appLanguage)
                java.util.Locale.setDefault(locale)
                val config = Configuration(this@MainActivity.resources.configuration)
                config.setLocale(locale)
                val localizedRes = this@MainActivity.createConfigurationContext(config).resources
                object : android.content.ContextWrapper(this@MainActivity) {
                    override fun getResources() = localizedRes
                }
            }
            val isRtl = remember(appLanguage) {
                val lang = java.util.Locale.forLanguageTag(appLanguage.replace('_', '-')).language
                lang in listOf("ar", "he", "iw", "fa", "ur")
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalAppLanguage provides appLanguage,
                LocalDeviceType provides effectiveDeviceType,
                LocalHasTouchScreen provides hasTouchScreen,
                androidx.compose.ui.platform.LocalLayoutDirection provides
                    if (isRtl) androidx.compose.ui.unit.LayoutDirection.Rtl
                    else androidx.compose.ui.unit.LayoutDirection.Ltr
            ) {
                ArflixTvTheme(
                    oledBlackBackground = oledBlackBackground,
                    accentColorName = accentColorName
                ) {
                    val startupState by startupViewModel.state.collectAsStateWithLifecycle()
                    ArflixApp(
                        profileRepository = profileRepository.get(),
                        profileManager = profileManager.get(),
                        watchlistRepository = watchlistRepository.get(),
                        oledBlackBackground = oledBlackBackground,
                        skipProfileSelection = skipProfileSelection,
                        preloadedCategories = startupState.categories,
                        preloadedHeroItem = startupState.heroItem,
                        preloadedHeroLogoUrl = startupState.heroLogoUrl,
                        preloadedLogoCache = startupState.logoCache,
                        onExitApp = { finish() }
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Re-apply immersive mode on TV when the window regains focus.
            if (detectDeviceType(this) == DeviceType.TV) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }
}

/**
 * Loading screen - app logo reveal.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ArvioLoadingScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")
    val reveal = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 920, easing = FastOutSlowInEasing)
        )
    }

    val logoAlpha by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // ADIK: splash logo.
        Image(
            painter = painterResource(id = R.drawable.adik_logo),
            contentDescription = "ADIK TV",
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .padding(horizontal = 24.dp)
                .graphicsLayer {
                    alpha = reveal.value * logoAlpha
                    val scale = 0.88f + (0.12f * reveal.value)
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - reveal.value) * 18.dp.toPx()
                },
        )
    }
}

/**
 * Root composable: waits for the active profile, then hosts navigation.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ArflixApp(
    profileRepository: ProfileRepository,
    profileManager: ProfileManager,
    watchlistRepository: WatchlistRepository,
    oledBlackBackground: Boolean = false,
    skipProfileSelection: Boolean? = null,
    preloadedCategories: List<com.arflix.tv.data.model.Category> = emptyList(),
    preloadedHeroItem: com.arflix.tv.data.model.MediaItem? = null,
    preloadedHeroLogoUrl: String? = null,
    preloadedLogoCache: Map<String, String> = emptyMap(),
    onExitApp: () -> Unit = {}
) {
    val context = LocalContext.current
    val activeProfileState by remember(profileRepository) {
        profileRepository.activeProfile.map { profile ->
            ActiveProfileLoadState.Loaded(profile) as ActiveProfileLoadState
        }
    }.collectAsStateWithLifecycle(initialValue = ActiveProfileLoadState.Loading)
    var startupIntroComplete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1350)
        startupIntroComplete = true
    }
    val activeProfile = (activeProfileState as? ActiveProfileLoadState.Loaded)?.profile
    val startupReady = skipProfileSelection != null &&
        activeProfileState is ActiveProfileLoadState.Loaded

    if (!startupReady || !startupIntroComplete) {
        ArvioLoadingScreen()
        return
    }

    val navController = rememberNavController()
    val appCoroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    val startDestination = if (skipProfileSelection == true && activeProfile != null) {
        Screen.Home.route
    } else {
        Screen.ProfileSelection.route
    }

    val deviceType = LocalDeviceType.current
    val isMobile = deviceType.isTouchDevice()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    // The player is edge-to-edge with no bottom bar.
    val isPlayerRoute = currentRoute?.contains("player") == true
    val showBottomBar = shouldShowBottomBar(
        isMobile = isMobile,
        currentRoute = currentRoute,
        isFullscreenRoute = isPlayerRoute
    )
    val applySystemBarsPadding = isMobile && !isPlayerRoute

    val hostActivity = remember(context) { context.findActivity() }
    LaunchedEffect(isPlayerRoute, isMobile) {
        if (isMobile && !isPlayerRoute) {
            val win = hostActivity?.window ?: (context as? ComponentActivity)?.window
            if (win != null) {
                @Suppress("DEPRECATION")
                win.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
                WindowInsetsControllerCompat(win, win.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                    show(WindowInsetsCompat.Type.systemBars())
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
    }

    val density = LocalDensity.current
    val barSpec = currentBottomBarSpec()
    val navigationInset = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    var measuredBarHeight by remember(barSpec, density.fontScale) { mutableStateOf(0.dp) }
    val barInset = if (showBottomBar) {
        maxOf(measuredBarHeight, (28 + (barSpec.itemHeightDp ?: 56)).dp + navigationInset)
    } else 0.dp
    val contentBehindBar = showBottomBar && currentRoute?.substringBefore('?') in setOf("home", "search")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = if (oledBlackBackground) {
                    Brush.linearGradient(colors = listOf(Color.Black, Color.Black))
                } else {
                    Brush.linearGradient(
                        colors = listOf(appBackgroundDark(), appBackgroundDark(), appBackgroundDark())
                    )
                }
            )
            .then(when {
                showBottomBar -> Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                applySystemBarsPadding -> Modifier.systemBarsPadding()
                else -> Modifier
            })
    ) {
        CompositionLocalProvider(LocalBottomBarInset provides if (contentBehindBar) barInset else 0.dp) {
            Box(modifier = Modifier.fillMaxSize().padding(bottom = if (contentBehindBar) 0.dp else barInset)) {
                AppNavigation(
                    navController = navController,
                    startDestination = startDestination,
                    preloadedCategories = preloadedCategories,
                    preloadedHeroItem = preloadedHeroItem,
                    preloadedHeroLogoUrl = preloadedHeroLogoUrl,
                    preloadedLogoCache = preloadedLogoCache,
                    currentProfile = activeProfile,
                    onSwitchProfile = {
                        appCoroutineScope.launch {
                            watchlistRepository.clearWatchlistCache()
                            profileManager.setCurrentProfileId("default")
                            profileManager.setCurrentProfileName("default")
                            profileRepository.clearActiveProfile()
                        }
                    },
                    onExitApp = onExitApp
                )
            }
        }

        if (showBottomBar) {
            AppBottomBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo("home") { inclusive = false }
                        launchSingleTop = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { measuredBarHeight = with(density) { it.height.toDp() } }
            )
        }
    }
}
