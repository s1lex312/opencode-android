package ai.opencode.android.ui.navigation

import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.chat.ChatDestination
import ai.opencode.android.ui.chat.ChatScreen
import ai.opencode.android.ui.config.AgentsScreen
import ai.opencode.android.ui.config.BehaviourScreen
import ai.opencode.android.ui.config.ChatSettingsScreen
import ai.opencode.android.ui.config.McpScreen
import ai.opencode.android.ui.config.PhoneAccessScreen
import ai.opencode.android.ui.diff.DiffScreen
import ai.opencode.android.ui.logs.LogsScreen
import ai.opencode.android.ui.providers.CustomProviderScreen
import ai.opencode.android.ui.providers.ProvidersScreen
import ai.opencode.android.ui.sessions.SessionsScreen
import ai.opencode.android.ui.settings.AboutScreen
import ai.opencode.android.ui.settings.AgentSettingsScreen
import ai.opencode.android.ui.settings.AppearanceSettingsScreen
import ai.opencode.android.ui.settings.ConnectionSettingsScreen
import ai.opencode.android.ui.settings.ServerProfilesScreen
import ai.opencode.android.ui.settings.SettingsScreen
import ai.opencode.android.ui.settings.SystemPromptsScreen
import ai.opencode.android.ui.terminal.TerminalScreen
import androidx.compose.runtime.LaunchedEffect
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

    const val SETTINGS_SERVERS = "settings/servers"
    const val SETTINGS_SYSTEM_PROMPTS = "settings/system-prompts"
    const val SETTINGS_CHAT = "settings/chat"
    const val SETTINGS_AGENTS = "settings/agents"
    const val SETTINGS_MCP = "settings/mcp"
    const val SETTINGS_BEHAVIOUR = "settings/behaviour"
    const val SETTINGS_PHONE = "settings/phone"

    const val PROVIDERS = "providers"
    const val PROVIDERS_CUSTOM = "providers/custom"

    const val TERMINAL = "terminal?cwd={cwd}"

    fun terminal(cwd: String?): String = if (cwd.isNullOrBlank()) "terminal" else "terminal?cwd=${Uri.encode(cwd)}"

    fun chat(sessionId: String): String = "chat/${Uri.encode(sessionId)}"

    fun diff(sessionId: String): String = "diff/${Uri.encode(sessionId)}"
}

private const val TRANSITION_MS = 260

/** Navigates without stacking a second copy of the destination on repeated taps. */
private fun NavHostController.open(route: String) = navigate(route) { launchSingleTop = true }

@Composable
fun OpencodeNavHost(container: AppContainer, openSessionRequest: String? = null, onSessionRequestHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    val back: () -> Unit = { navController.popBackStack() }

    // Taps on "done" / permission notifications land on their chat.
    LaunchedEffect(openSessionRequest) {
        val sessionId = openSessionRequest ?: return@LaunchedEffect
        navController.navigate(Routes.chat(sessionId)) {
            popUpTo(Routes.SESSIONS)
            launchSingleTop = true
        }
        onSessionRequestHandled()
    }

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
                onOpenSession = { forked ->
                    navController.navigate(Routes.chat(forked)) {
                        popUpTo(Routes.SESSIONS)
                        launchSingleTop = true
                    }
                },
                onOpenTerminal = { cwd -> navController.open(Routes.terminal(cwd)) },
                onOpenSystemPrompts = { navController.open(Routes.SETTINGS_SYSTEM_PROMPTS) },
                onNavigate = { destination ->
                    when (destination) {
                        ChatDestination.SESSIONS -> navController.popBackStack(Routes.SESSIONS, inclusive = false)
                        ChatDestination.SETTINGS -> navController.open(Routes.SETTINGS)
                        ChatDestination.CHAT_SETTINGS -> navController.open(Routes.SETTINGS_CHAT)
                        ChatDestination.AGENTS -> navController.open(Routes.SETTINGS_AGENTS)
                        ChatDestination.MCP -> navController.open(Routes.SETTINGS_MCP)
                        ChatDestination.PERMISSIONS -> navController.open(Routes.SETTINGS_BEHAVIOUR)
                        ChatDestination.TERMINAL -> navController.open(Routes.terminal(null))
                        ChatDestination.DIFF -> navController.open(Routes.diff(sessionId))
                        ChatDestination.SYSTEM_PROMPTS -> navController.open(Routes.SETTINGS_SYSTEM_PROMPTS)
                        ChatDestination.PROVIDERS -> navController.open(Routes.PROVIDERS)
                    }
                },
            )
        }

        composable(Routes.SETTINGS_CHAT) {
            ChatSettingsScreen(container = container, onBack = back, onOpenAppearance = { navController.open(Routes.SETTINGS_APPEARANCE) })
        }

        composable(Routes.SETTINGS_AGENTS) {
            AgentsScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS_MCP) {
            McpScreen(container = container, onBack = back, onOpenPhoneAccess = { navController.open(Routes.SETTINGS_PHONE) })
        }

        composable(Routes.SETTINGS_BEHAVIOUR) {
            BehaviourScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS_PHONE) {
            PhoneAccessScreen(
                container = container,
                onBack = back,
                onOpenPermissions = { navController.open(Routes.SETTINGS_BEHAVIOUR) },
                onOpenMcp = { navController.open(Routes.SETTINGS_MCP) },
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
                onOpenServers = { navController.open(Routes.SETTINGS_SERVERS) },
                onOpenProviders = { navController.open(Routes.PROVIDERS) },
                onOpenAgent = { navController.open(Routes.SETTINGS_AGENT) },
                onOpenAppearance = { navController.open(Routes.SETTINGS_APPEARANCE) },
                onOpenAbout = { navController.open(Routes.SETTINGS_ABOUT) },
                onOpenChat = { navController.open(Routes.SETTINGS_CHAT) },
                onOpenAgents = { navController.open(Routes.SETTINGS_AGENTS) },
                onOpenMcp = { navController.open(Routes.SETTINGS_MCP) },
                onOpenBehaviour = { navController.open(Routes.SETTINGS_BEHAVIOUR) },
                onOpenPhone = { navController.open(Routes.SETTINGS_PHONE) },
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
                onOpenSystemPrompts = { navController.open(Routes.SETTINGS_SYSTEM_PROMPTS) },
            )
        }

        composable(Routes.SETTINGS_SERVERS) {
            ServerProfilesScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS_SYSTEM_PROMPTS) {
            SystemPromptsScreen(container = container, onBack = back)
        }

        composable(
            route = Routes.TERMINAL,
            arguments = listOf(
                navArgument("cwd") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            TerminalScreen(container = container, cwd = entry.arguments?.getString("cwd"), onBack = back)
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
