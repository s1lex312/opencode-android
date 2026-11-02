package ai.opencode.android.ui.navigation

import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.chat.ChatScreen
import ai.opencode.android.ui.diff.DiffScreen
import ai.opencode.android.ui.logs.LogsScreen
import ai.opencode.android.ui.providers.CustomProviderScreen
import ai.opencode.android.ui.providers.ProvidersScreen
import ai.opencode.android.ui.sessions.SessionsScreen
import ai.opencode.android.ui.settings.AboutScreen
import ai.opencode.android.ui.settings.AgentSettingsScreen
import ai.opencode.android.ui.settings.AppearanceSettingsScreen
import ai.opencode.android.ui.settings.ConnectionSettingsScreen
import ai.opencode.android.ui.settings.SettingsScreen
import ai.opencode.android.ui.setup.SetupScreen
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val SESSIONS = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val DIFF = "diff/{sessionId}"
    const val SETUP = "setup"
    const val LOGS = "logs"

    const val SETTINGS = "settings"
    const val SETTINGS_CONNECTION = "settings/connection"
    const val SETTINGS_AGENT = "settings/agent"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_ABOUT = "settings/about"

    const val PROVIDERS = "providers"
    const val PROVIDERS_CUSTOM = "providers/custom"

    fun chat(sessionId: String): String = "chat/${Uri.encode(sessionId)}"

    fun diff(sessionId: String): String = "diff/${Uri.encode(sessionId)}"
}

private const val TRANSITION_MS = 260

/** Navigates without stacking a second copy of the destination on repeated taps. */
private fun NavHostController.open(route: String) = navigate(route) { launchSingleTop = true }

@Composable
fun OpencodeNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val back: () -> Unit = { navController.popBackStack() }

    NavHost(
        navController = navController,
        startDestination = Routes.SESSIONS,
        enterTransition = {
            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(TRANSITION_MS)) +
                fadeIn(tween(TRANSITION_MS))
        },
        exitTransition = { fadeOut(tween(TRANSITION_MS / 2)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
        popExitTransition = {
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(TRANSITION_MS)) +
                fadeOut(tween(TRANSITION_MS))
        },
    ) {
        composable(Routes.SESSIONS) {
            SessionsScreen(
                container = container,
                onOpenSession = { sessionId -> navController.open(Routes.chat(sessionId)) },
                onOpenSettings = { navController.open(Routes.SETTINGS) },
                onOpenSetup = { navController.open(Routes.SETUP) },
                onOpenProviders = { navController.open(Routes.PROVIDERS) },
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            ChatScreen(
                container = container,
                sessionId = sessionId,
                onBack = back,
                onOpenDiff = { navController.open(Routes.diff(sessionId)) },
                onOpenSettings = { navController.open(Routes.SETTINGS) },
                onOpenProviders = { navController.open(Routes.PROVIDERS) },
            )
        }

        composable(
            route = Routes.DIFF,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            DiffScreen(
                container = container,
                sessionId = entry.arguments?.getString("sessionId").orEmpty(),
                onBack = back,
            )
        }

        composable(Routes.SETUP) {
            SetupScreen(container = container, onBack = back, onOpenLogs = { navController.open(Routes.LOGS) })
        }

        composable(Routes.LOGS) {
            LogsScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = back,
                onOpenConnection = { navController.open(Routes.SETTINGS_CONNECTION) },
                onOpenProviders = { navController.open(Routes.PROVIDERS) },
                onOpenAgent = { navController.open(Routes.SETTINGS_AGENT) },
                onOpenAppearance = { navController.open(Routes.SETTINGS_APPEARANCE) },
                onOpenAbout = { navController.open(Routes.SETTINGS_ABOUT) },
            )
        }

        composable(Routes.SETTINGS_CONNECTION) {
            ConnectionSettingsScreen(
                container = container,
                onBack = back,
                onOpenSetup = { navController.open(Routes.SETUP) },
                onOpenLogs = { navController.open(Routes.LOGS) },
            )
        }

        composable(Routes.SETTINGS_AGENT) {
            AgentSettingsScreen(
                container = container,
                onBack = back,
                onOpenProviders = { navController.open(Routes.PROVIDERS) },
            )
        }

        composable(Routes.SETTINGS_APPEARANCE) {
            AppearanceSettingsScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS_ABOUT) {
            AboutScreen(container = container, onBack = back)
        }

        composable(Routes.PROVIDERS) {
            ProvidersScreen(
                container = container,
                onBack = back,
                onAddCustomProvider = { navController.open(Routes.PROVIDERS_CUSTOM) },
            )
        }

        composable(Routes.PROVIDERS_CUSTOM) {
            CustomProviderScreen(container = container, onBack = back)
        }
    }
}
