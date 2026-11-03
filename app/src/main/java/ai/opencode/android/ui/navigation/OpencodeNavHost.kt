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
import ai.opencode.android.ui.git.GitScreen
import ai.opencode.android.ui.share.ShareScreen
import ai.opencode.android.ui.tools.EnvScreen
import ai.opencode.android.ui.tools.HooksScreen
import ai.opencode.android.ui.tools.SnippetsScreen
import ai.opencode.android.ui.tools.StatsScreen
import ai.opencode.android.ui.tools.TasksScreen
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
    const val SETTINGS_BACKUP = "settings/backup"

    const val SETTINGS_SERVERS = "settings/servers"
    const val SETTINGS_SYSTEM_PROMPTS = "settings/system-prompts"
    const val SETTINGS_CHAT = "settings/chat"
    const val SETTINGS_AGENTS = "settings/agents"
    const val SETTINGS_MCP = "settings/mcp"
    const val SETTINGS_BEHAVIOUR = "settings/behaviour"
    const val SETTINGS_PHONE = "settings/phone"
    const val SETTINGS_PROXY = "settings/proxy"
    const val SETTINGS_UI = "settings/appearance/customize"
    const val SETTINGS_ALL = "settings/all"
    const val SETTINGS_PROFILES = "settings/profiles"
    const val SETTINGS_PLUGINS = "settings/plugins"
    const val SETTINGS_DIAGNOSTICS = "settings/diagnostics"
    const val SETTINGS_CONFIG = "settings/opencode-config"

    const val PROVIDERS = "providers"
    const val PROVIDERS_CUSTOM = "providers/custom"

    const val TERMINAL = "terminal?cwd={cwd}"
    const val GIT = "git?cwd={cwd}"
    const val TASKS = "tasks"
    const val SNIPPETS = "snippets"
    const val HOOKS = "hooks"
    const val STATS = "stats"
    const val ENV = "settings/env"
    const val SHARE = "share"

    fun git(cwd: String?): String = if (cwd.isNullOrBlank()) "git" else "git?cwd=${Uri.encode(cwd)}"

    fun terminal(cwd: String?): String = if (cwd.isNullOrBlank()) "terminal" else "terminal?cwd=${Uri.encode(cwd)}"

    fun chat(sessionId: String): String = "chat/${Uri.encode(sessionId)}"

    fun diff(sessionId: String): String = "diff/${Uri.encode(sessionId)}"
}

private const val TRANSITION_MS = 260

/** Navigates without stacking a second copy of the destination on repeated taps. */
private fun NavHostController.open(route: String) = navigate(route) { launchSingleTop = true }

@Composable
fun OpencodeNavHost(
    container: AppContainer,
    openSessionRequest: String? = null,
    onSessionRequestHandled: () -> Unit = {},
    shareRequest: Int = 0,
) {
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

    // Something was shared from another app.
    LaunchedEffect(shareRequest) {
        if (shareRequest > 0 && container.pendingShare.value != null) navController.open(Routes.SHARE)
    }

    val animate = ai.opencode.android.ui.theme.LocalUiTokens.current.animations
    NavHost(
        navController = navController,
        startDestination = Routes.SESSIONS,
        enterTransition = {
            if (!animate) {
                androidx.compose.animation.EnterTransition.None
            } else {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(TRANSITION_MS)) +
                    fadeIn(tween(TRANSITION_MS))
            }
        },
        exitTransition = { if (animate) fadeOut(tween(TRANSITION_MS / 2)) else androidx.compose.animation.ExitTransition.None },
        popEnterTransition = { if (animate) fadeIn(tween(TRANSITION_MS)) else androidx.compose.animation.EnterTransition.None },
        popExitTransition = {
            if (!animate) {
                androidx.compose.animation.ExitTransition.None
            } else {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(TRANSITION_MS)) +
                    fadeOut(tween(TRANSITION_MS))
            }
        },
    ) {
        composable(Routes.SESSIONS) {
            SessionsScreen(
                container = container,
                onOpenSession = { sessionId -> navController.open(Routes.chat(sessionId)) },
                onOpenSettings = { navController.open(Routes.SETTINGS) },
                onOpenSetup = { navController.open(Routes.SETUP) },
                onOpenProviders = { navController.open(Routes.PROVIDERS) },
                onOpenTasks = { navController.open(Routes.TASKS) },
                onOpenStats = { navController.open(Routes.STATS) },
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
                        ChatDestination.GIT -> navController.open(Routes.git(null))
                        ChatDestination.TASKS -> navController.open(Routes.TASKS)
                        ChatDestination.SNIPPETS -> navController.open(Routes.SNIPPETS)
                        ChatDestination.HOOKS -> navController.open(Routes.HOOKS)
                        ChatDestination.STATS -> navController.open(Routes.STATS)
                        ChatDestination.ENV -> navController.open(Routes.ENV)
                    }
                },
                onOpenGit = { cwd -> navController.open(Routes.git(cwd)) },
            )
        }

        composable(
            route = Routes.GIT,
            arguments = listOf(
                navArgument("cwd") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            GitScreen(container = container, cwd = entry.arguments?.getString("cwd"), onBack = back)
        }

        composable(Routes.TASKS) {
            TasksScreen(container = container, onBack = back, onOpenSession = { navController.open(Routes.chat(it)) })
        }

        composable(Routes.SNIPPETS) {
            SnippetsScreen(container = container, onBack = back)
        }

        composable(Routes.HOOKS) {
            HooksScreen(container = container, onBack = back)
        }

        composable(Routes.STATS) {
            StatsScreen(container = container, onBack = back)
        }

        composable(Routes.ENV) {
            EnvScreen(container = container, onBack = back, onOpenMcp = { navController.open(Routes.SETTINGS_MCP) })
        }

        composable(Routes.SHARE) {
            ShareScreen(
                container = container,
                onBack = back,
                onOpenSession = { sessionId ->
                    navController.navigate(Routes.chat(sessionId)) {
                        popUpTo(Routes.SESSIONS)
                        launchSingleTop = true
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
            McpScreen(
                container = container,
                onBack = back,
                onOpenPhoneAccess = { navController.open(Routes.SETTINGS_PHONE) },
                onOpenEnv = { navController.open(Routes.ENV) },
            )
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
                onOpenHooks = { navController.open(Routes.HOOKS) },
                onOpenSnippets = { navController.open(Routes.SNIPPETS) },
                onOpenStats = { navController.open(Routes.STATS) },
                onOpenEnv = { navController.open(Routes.ENV) },
                onOpenTasks = { navController.open(Routes.TASKS) },
                onOpenBackup = { navController.open(Routes.SETTINGS_BACKUP) },
                onOpenProxy = { navController.open(Routes.SETTINGS_PROXY) },
                onOpenPlugins = { navController.open(Routes.SETTINGS_PLUGINS) },
                onOpenConfig = { navController.open(Routes.SETTINGS_CONFIG) },
                onOpenAll = { navController.open(Routes.SETTINGS_ALL) },
                onOpenProfiles = { navController.open(Routes.SETTINGS_PROFILES) },
                onOpenDiagnostics = { navController.open(Routes.SETTINGS_DIAGNOSTICS) },
                onOpenLogs = { navController.open(Routes.LOGS) },
            )
        }

        composable(Routes.SETTINGS_PROXY) {
            ai.opencode.android.ui.settings.ProxyScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS_UI) {
            ai.opencode.android.ui.settings.UiCustomizeScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS_ALL) {
            ai.opencode.android.ui.settings.AllSettingsScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS_PROFILES) {
            ai.opencode.android.ui.settings.SettingsProfilesScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS_PLUGINS) {
            ai.opencode.android.ui.settings.PluginsScreen(container = container, onBack = back, onOpenEnv = { navController.open(Routes.ENV) })
        }

        composable(Routes.SETTINGS_DIAGNOSTICS) {
            ai.opencode.android.ui.settings.DiagnosticsScreen(container = container, onBack = back, onOpenLogs = { navController.open(Routes.LOGS) })
        }

        composable(Routes.SETTINGS_CONFIG) {
            ai.opencode.android.ui.config.ConfigEditorScreen(container = container, onBack = back)
        }

        composable(Routes.SETTINGS_BACKUP) {
            ai.opencode.android.ui.backup.BackupScreen(container = container, onBack = back)
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
            AppearanceSettingsScreen(container = container, onBack = back, onOpenCustomize = { navController.open(Routes.SETTINGS_UI) })
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
