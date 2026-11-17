package com.arflix.tv.navigation

import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arflix.tv.data.model.Category
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.screens.details.DetailsScreen
import com.arflix.tv.ui.screens.home.CategoryViewAllScreen
import com.arflix.tv.ui.screens.home.HomeScreen
import com.arflix.tv.ui.screens.player.VlcPlayerScreen
import com.arflix.tv.ui.screens.profile.ProfileSelectionScreen
import com.arflix.tv.ui.screens.search.SearchScreen
import com.arflix.tv.ui.screens.settings.SettingsScreen

/**
 * Navigation destinations
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Search : Screen("search")
    data object CategoryViewAll : Screen("home_category/{categoryId}") {
        fun createRoute(categoryId: String): String {
            return "home_category/${android.net.Uri.encode(categoryId)}"
        }
    }
    data object Settings : Screen("settings") {
        fun createRoute(initialSection: String? = null): String =
            if (initialSection != null) "settings?initialSection=$initialSection" else "settings"
    }
    data object ProfileSelection : Screen("profile_selection")

    data object Details : Screen("details/{mediaType}/{mediaId}?initialSeason={initialSeason}&initialEpisode={initialEpisode}") {
        fun createRoute(
            mediaType: MediaType,
            mediaId: Int,
            initialSeason: Int? = null,
            initialEpisode: Int? = null
        ): String {
            val base = "details/${mediaType.name.lowercase()}/$mediaId"
            val params = mutableListOf<String>()
            initialSeason?.let { params.add("initialSeason=$it") }
            initialEpisode?.let { params.add("initialEpisode=$it") }
            return if (params.isNotEmpty()) "$base?${params.joinToString("&")}" else base
        }
    }
}

/** Everything the VLC player needs to play one local drive file. */
data class LocalPlayRequest(
    val uri: String,
    val title: String,
    val mediaType: MediaType,
    val tmdbId: Int,
    val season: Int? = null,
    val episode: Int? = null,
    val startPositionMs: Long? = null
)

/** Route for the libVLC player; arguments match VlcPlayerViewModel's ARG_* keys. */
object VlcPlayerRoute {
    const val route = "vlc_player?uri={uri}&title={title}&tmdbId={tmdbId}&type={type}&season={season}&episode={episode}&startMs={startMs}"

    fun create(request: LocalPlayRequest): String {
        fun enc(value: String) = android.net.Uri.encode(value)
        return "vlc_player?uri=${enc(request.uri)}" +
            "&title=${enc(request.title)}" +
            "&tmdbId=${request.tmdbId}" +
            "&type=${if (request.mediaType == MediaType.TV) "tv" else "movie"}" +
            "&season=${request.season ?: -1}" +
            "&episode=${request.episode ?: -1}" +
            "&startMs=${request.startPositionMs ?: -1L}"
    }
}

internal fun NavHostController.navigateToProfileSelection() {
    navigate(Screen.ProfileSelection.route) {
        // The start destination may already have been removed after profile selection.
        popUpTo(graph.id) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Main navigation graph
 */
@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.ProfileSelection.route,
    preloadedCategories: List<Category> = emptyList(),
    preloadedHeroItem: MediaItem? = null,
    preloadedHeroLogoUrl: String? = null,
    preloadedLogoCache: Map<String, String> = emptyMap(),
    currentProfile: Profile? = null,
    onSwitchProfile: () -> Unit = {},
    onExitApp: () -> Unit = {}
) {
    val navigateTopLevel: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val navigateHome: () -> Unit = {
        // Pop back to the EXISTING Home entry (keeps its HomeViewModel alive) rather than
        // rebuilding Home; fall back to navigating when Home isn't on the stack.
        if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Home.route) { inclusive = true; saveState = false }
                launchSingleTop = true
                restoreState = false
            }
        }
    }

    val switchProfile: () -> Unit = {
        onSwitchProfile()
        navController.navigateToProfileSelection()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Pure ~250ms crossfade for every screen transition (TV style, no slides).
        enterTransition = { fadeIn(tween(280, easing = FastOutSlowInEasing)) },
        exitTransition = { fadeOut(tween(240, easing = FastOutSlowInEasing)) },
        popEnterTransition = { fadeIn(tween(280, easing = FastOutSlowInEasing)) },
        popExitTransition = { fadeOut(tween(240, easing = FastOutSlowInEasing)) }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                preloadedCategories = preloadedCategories,
                preloadedHeroItem = preloadedHeroItem,
                preloadedHeroLogoUrl = preloadedHeroLogoUrl,
                preloadedLogoCache = preloadedLogoCache,
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId, initialSeason, initialEpisode ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId, initialSeason, initialEpisode))
                },
                onNavigateToCollection = { },
                onNavigateToCategory = { categoryId ->
                    navController.navigate(Screen.CategoryViewAll.createRoute(categoryId))
                },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToWatchlist = { },
                onNavigateToTv = { _, _ -> },
                onNavigateToPlayer = { _, _, _, _, _ -> },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = switchProfile,
                onProfileSwitched = {
                    // In-place switch from the avatar dropdown: rebuild the Home
                    // entry so HomeViewModel reloads for the new profile.
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true; saveState = false }
                        launchSingleTop = true
                        restoreState = false
                    }
                },
                onExitApp = onExitApp
            )
        }

        composable(Screen.Search.route) {
            SearchScreen(
                currentProfile = currentProfile,
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onNavigateToHome = { navigateHome() },
                onNavigateToWatchlist = { },
                onNavigateToTv = { },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = switchProfile,
                onBack = { navigateHome() }
            )
        }

        composable(
            route = "settings?initialSection={initialSection}",
            arguments = listOf(
                navArgument("initialSection") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            SettingsScreen(
                currentProfile = currentProfile,
                autoStartCloudAuth = false,
                initialSection = backStackEntry.arguments?.getString("initialSection"),
                installPackUrl = null,
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToTv = { },
                onNavigateToWatchlist = { },
                onSwitchProfile = switchProfile,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.ProfileSelection.route) {
            ProfileSelectionScreen(
                onProfileSelected = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.ProfileSelection.route) { inclusive = true }
                    }
                },
                onShowAddProfile = { /* Handled internally by ProfileSelectionScreen */ },
                onConnectCloud = { },
                isCloudConnected = false
            )
        }

        // Home row "View all" grid. Shares Home's ViewModel (Home stays on the back stack).
        composable(
            route = Screen.CategoryViewAll.route,
            arguments = listOf(navArgument("categoryId") { type = NavType.StringType })
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getString("categoryId").orEmpty()
            val homeEntry = remember(backStackEntry) {
                runCatching { navController.getBackStackEntry(Screen.Home.route) }.getOrNull()
            }
            if (categoryId.isBlank() || homeEntry == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
                return@composable
            }
            CategoryViewAllScreen(
                categoryId = categoryId,
                viewModel = hiltViewModel(homeEntry),
                onNavigateToDetails = { mediaType, mediaId ->
                    navController.navigate(Screen.Details.createRoute(mediaType, mediaId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Details.route,
            arguments = listOf(
                navArgument("mediaType") { type = NavType.StringType },
                navArgument("mediaId") { type = NavType.IntType },
                navArgument("initialSeason") {
                    type = NavType.IntType
                    defaultValue = -1
                },
                navArgument("initialEpisode") {
                    type = NavType.IntType
                    defaultValue = -1
                }
            )
        ) { backStackEntry ->
            val mediaTypeStr = backStackEntry.arguments?.getString("mediaType") ?: "movie"
            val mediaId = backStackEntry.arguments?.getInt("mediaId") ?: 0
            if (mediaId <= 0) {
                navigateHome()
                return@composable
            }
            val initialSeason = backStackEntry.arguments?.getInt("initialSeason")?.takeIf { it >= 0 }
            val initialEpisode = backStackEntry.arguments?.getInt("initialEpisode")?.takeIf { it >= 0 }
            val mediaType = if (mediaTypeStr == "tv") MediaType.TV else MediaType.MOVIE

            DetailsScreen(
                mediaType = mediaType,
                mediaId = mediaId,
                initialSeason = initialSeason,
                initialEpisode = initialEpisode,
                currentProfile = currentProfile,
                onPlayLocal = { request ->
                    navController.navigate(VlcPlayerRoute.create(request))
                },
                onNavigateToDetails = { type, id ->
                    navController.navigate(Screen.Details.createRoute(type, id))
                },
                onNavigateToCollection = { },
                onNavigateToHome = { navigateHome() },
                onNavigateToSearch = { navigateTopLevel(Screen.Search.route) },
                onNavigateToTv = { },
                onNavigateToWatchlist = { },
                onNavigateToSettings = { navigateTopLevel(Screen.Settings.route) },
                onSwitchProfile = {
                    onSwitchProfile()
                    navController.navigate(Screen.ProfileSelection.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // libVLC player (local drive files)
        composable(
            route = VlcPlayerRoute.route,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("tmdbId") { type = NavType.IntType; defaultValue = 0 },
                navArgument("type") { type = NavType.StringType; defaultValue = "movie" },
                navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                navArgument("episode") { type = NavType.IntType; defaultValue = -1 },
                navArgument("startMs") { type = NavType.LongType; defaultValue = -1L }
            ),
            exitTransition = { ExitTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            VlcPlayerScreen(onBack = { navController.popBackStack() })
        }
    }
}
