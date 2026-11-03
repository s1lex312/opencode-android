package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.ConnectionMode
import ai.opencode.android.settings.ThemeMode
import ai.opencode.android.ui.components.AdaptiveContent
import ai.opencode.android.ui.components.ConnectionBadge
import ai.opencode.android.ui.components.IconBadge
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.connectionLabel
import ai.opencode.android.ui.i18n.AppLanguage
import ai.opencode.android.ui.text.UiText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

/**
 * Settings hub: a status card for where opencode runs, then one row per focused sub-screen.
 * Nothing is edited here; every row opens the screen that owns that setting.
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenAgents: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenBehaviour: () -> Unit,
    onOpenPhone: () -> Unit,
    onOpenHooks: () -> Unit = {},
    onOpenSnippets: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenEnv: () -> Unit = {},
    onOpenTasks: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = remember { AppLanguage.current() }
    val (connectionText, _) = connectionLabel(state.connection)

    SettingsPage(title = stringResource(R.string.settings_title), onBack = onBack) {
        StatusHeader(state = state, onClick = onOpenConnection)

        SettingsGroup(title = stringResource(R.string.settings_group_server)) {
            SettingsItem(
                title = stringResource(R.string.settings_connection),
                subtitle = stringResource(R.string.settings_pair, modeLabel(state.connectionMode), connectionText),
                icon = Icons.Default.Dns,
                showChevron = true,
                onClick = onOpenConnection,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.servers_title),
                subtitle = state.settings.activeServerProfile?.name
                    ?: stringResource(R.string.servers_hub_subtitle, state.settings.serverProfiles.size),
                icon = Icons.Default.SwapHoriz,
                showChevron = true,
                onClick = onOpenServers,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_providers),
                subtitle = stringResource(R.string.settings_providers_subtitle),
                icon = Icons.Default.Key,
                showChevron = true,
                onClick = onOpenProviders,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_group_agent)) {
            SettingsItem(
                title = stringResource(R.string.agents_title),
                subtitle = stringResource(R.string.agents_hub_subtitle),
                icon = Icons.Default.SmartToy,
                showChevron = true,
                onClick = onOpenAgents,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.mcp_title),
                subtitle = stringResource(R.string.mcp_hub_subtitle),
                icon = Icons.Default.Hub,
                showChevron = true,
                onClick = onOpenMcp,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.behaviour_title),
                subtitle = stringResource(R.string.behaviour_hub_subtitle),
                icon = Icons.Default.Security,
                showChevron = true,
                onClick = onOpenBehaviour,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.phone_title),
                subtitle = stringResource(R.string.phone_hub_subtitle),
                icon = Icons.Default.PhoneAndroid,
                showChevron = true,
                onClick = onOpenPhone,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.env_title),
                subtitle = stringResource(R.string.env_hub_subtitle),
                icon = Icons.Default.Download,
                showChevron = true,
                onClick = onOpenEnv,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_group_workflow)) {
            SettingsItem(
                title = stringResource(R.string.hooks_title),
                subtitle = stringResource(R.string.hooks_hub_subtitle),
                icon = Icons.Default.Webhook,
                showChevron = true,
                onClick = onOpenHooks,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.snippets_title),
                subtitle = stringResource(R.string.snippets_hub_subtitle),
                icon = Icons.Default.Bolt,
                showChevron = true,
                onClick = onOpenSnippets,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.tasks_title),
                subtitle = stringResource(R.string.tasks_hub_subtitle),
                icon = Icons.Default.Schedule,
                showChevron = true,
                onClick = onOpenTasks,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.stats_title),
                subtitle = stringResource(R.string.stats_hub_subtitle),
                icon = Icons.Default.BarChart,
                showChevron = true,
                onClick = onOpenStats,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_group_preferences)) {
            SettingsItem(
                title = stringResource(R.string.chat_settings_title),
                subtitle = stringResource(R.string.chat_settings_hub_subtitle),
                icon = Icons.Default.Forum,
                showChevron = true,
                onClick = onOpenChat,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_agent_model),
                subtitle = stringResource(
                    R.string.settings_pair,
                    state.settings.model?.displayName ?: stringResource(R.string.settings_model_short_default),
                    state.settings.agent.ifBlank { stringResource(R.string.settings_agent_short_default) },
                ),
                icon = Icons.Default.AutoAwesome,
                showChevron = true,
                onClick = onOpenAgent,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_appearance),
                subtitle = stringResource(
                    R.string.settings_pair,
                    themeSummary(state.settings.appearance.themeMode),
                    languageLabel(language, short = true),
                ),
                icon = Icons.Default.Palette,
                showChevron = true,
                onClick = onOpenAppearance,
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.settings_about),
                subtitle = stringResource(R.string.settings_about_subtitle),
                icon = Icons.Default.Info,
                showChevron = true,
                onClick = onOpenAbout,
            )
        }
    }
}

@Composable
private fun StatusHeader(state: SettingsUiState, onClick: () -> Unit) {
    val local = state.isLocalMode
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    icon = if (local) Icons.Default.PhoneAndroid else Icons.Default.Dns,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    container = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                ConnectionBadge(state.connection)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(if (local) R.string.settings_hub_local_title else R.string.settings_hub_remote_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when {
                    !local -> state.settings.serverUrl
                    state.envChecked && !state.envInstalled -> stringResource(R.string.settings_hub_local_setup)
                    else -> stringResource(R.string.settings_hub_local_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------------- shared by the settings screens

/**
 * Scaffold shared by every settings screen: a collapsing large top bar, a back arrow, an optional
 * snackbar host and a width-capped scrolling column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
    ) { padding ->
        AdaptiveContent(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
                content = content,
            )
        }
    }
}

/**
 * Shows a ViewModel's one-shot [message] as a snackbar and immediately marks it consumed, so it is
 * not repeated after rotation. The snackbar runs in a scope that outlives the message key.
 */
@Composable
internal fun MessageSnackbar(message: UiText?, hostState: SnackbarHostState, onShown: () -> Unit) {
    val scope = rememberCoroutineScope()
    val text = message?.resolve()
    LaunchedEffect(message) {
        if (message != null && text != null) {
            scope.launch { hostState.showSnackbar(text) }
            onShown()
        }
    }
}

@Composable
internal fun modeLabel(mode: ConnectionMode): String = stringResource(
    when (mode) {
        ConnectionMode.LOCAL -> R.string.settings_mode_local
        ConnectionMode.REMOTE -> R.string.settings_mode_remote
    },
)

@Composable
internal fun themeSummary(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_summary_system
        ThemeMode.LIGHT -> R.string.settings_theme_summary_light
        ThemeMode.DARK -> R.string.settings_theme_summary_dark
    },
)

/** Endonym for real languages; "System default" (or the shorter hub wording) in the UI language. */
@Composable
internal fun languageLabel(language: AppLanguage, short: Boolean = false): String = when {
    language != AppLanguage.SYSTEM -> language.endonym
    short -> stringResource(R.string.settings_language_system_short)
    else -> stringResource(R.string.settings_language_system)
}
