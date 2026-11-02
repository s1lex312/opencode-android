package ai.opencode.android.ui.navigation

import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.chat.ChatScreen
import ai.opencode.android.ui.diff.DiffScreen
import ai.opencode.android.ui.logs.LogsScreen
import ai.opencode.android.ui.setup.SetupScreen
import ai.opencode.android.ui.sessions.SessionsScreen
import ai.opencode.android.ui.settings.SettingsScreen
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val SESSIONS = "sessions"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{sessionId}"
    const val DIFF = "diff/{sessionId}"
    const val SETUP = "setup"
    const val LOGS = "logs"

    fun chat(sessionId: String): String = "chat/${Uri.encode(sessionId)}"

    fun diff(sessionId: String): String = "diff/${Uri.encode(sessionId)}"
}

@Composable
fun OpencodeNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.SESSIONS) {
        composable(Routes.SESSIONS) {
            SessionsScreen(
                container = container,
                onOpenSession = { sessionId -> navController.navigate(Routes.chat(sessionId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenSetup = { navController.navigate(Routes.SETUP) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenSetup = { navController.navigate(Routes.SETUP) },
                onOpenLogs = { navController.navigate(Routes.LOGS) },
            )
        }

        composable(Routes.SETUP) {
            SetupScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpenLogs = { navController.navigate(Routes.LOGS) },
            )
        }

        composable(Routes.LOGS) {
            LogsScreen(
                container = container,
                onBack = { navController.popBackStack() },
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
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenDiff = { navController.navigate(Routes.diff(sessionId)) },
            )
        }

        composable(
            route = Routes.DIFF,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            DiffScreen(
                container = container,
                sessionId = sessionId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
