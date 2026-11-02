package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsRadioItem
import ai.opencode.android.ui.components.SettingsSwitchItem
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import ai.opencode.android.ui.models.ModelPickerSheet
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Settings → Agent & model: default model, default agent and how permission requests are answered. */
@Composable
fun AgentSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSystemPrompts: () -> Unit,
) {
    val viewModel: AgentSettingsViewModel = viewModel(factory = AgentSettingsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    SettingsPage(title = stringResource(R.string.settings_agent_title), onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.settings_model_group)) {
            val model = state.model
            SettingsItem(
                title = stringResource(R.string.settings_default_model),
                subtitle = model?.let { "${it.providerID} / ${it.modelID}" }
                    ?: stringResource(R.string.settings_default_model_server),
                icon = Icons.Default.AutoAwesome,
                showChevron = true,
                onClick = {
                    pickerOpen = true
                    if (state.models.isEmpty()) viewModel.loadModels()
                },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_providers),
                subtitle = stringResource(R.string.settings_providers_hint),
                icon = Icons.Default.Hub,
                showChevron = true,
                onClick = onOpenProviders,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_agent_group)) {
            SettingsRadioItem(
                title = stringResource(R.string.settings_agent_server_default),
                subtitle = stringResource(R.string.settings_agent_server_default_hint),
                selected = state.agent.isBlank(),
                onClick = { viewModel.selectAgent("") },
            )
            when {
                state.agentsLoading && state.agents.isEmpty() -> LoadingIndicator()
                state.agentsError != null && state.agents.isEmpty() -> Text(
                    text = state.agentsError!!.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            state.agents.forEach { agent ->
                SettingsRadioItem(
                    title = agent.name,
                    subtitle = agent.description,
                    selected = state.agent == agent.name,
                    onClick = { viewModel.selectAgent(agent.name) },
                )
            }
            // A saved agent the current server does not offer stays visible instead of vanishing.
            if (state.agent.isNotBlank() && state.agentsLoaded && state.agents.none { it.name == state.agent }) {
                SettingsRadioItem(
                    title = state.agent,
                    subtitle = stringResource(R.string.settings_agent_unavailable),
                    selected = true,
                    onClick = {},
                )
            }
        }

        val appSettings by container.settingsRepository.settings.collectAsState(initial = ai.opencode.android.settings.AppSettings())
        val settingsScope = rememberCoroutineScope()
        SettingsGroup(title = stringResource(R.string.system_prompts_title)) {
            SettingsItem(
                title = appSettings.activeSystemPrompt?.name?.ifBlank { null }
                    ?: stringResource(R.string.chat_system_prompt_none),
                subtitle = stringResource(R.string.system_prompts_summary, appSettings.systemPrompts.size),
                icon = Icons.Default.Description,
                showChevron = true,
                onClick = onOpenSystemPrompts,
            )
        }

        SettingsGroup(title = stringResource(R.string.notify_group)) {
            SettingsSwitchItem(
                title = stringResource(R.string.notify_toggle),
                subtitle = stringResource(R.string.notify_toggle_hint),
                checked = appSettings.notifications,
                onCheckedChange = { enabled ->
                    settingsScope.launch { container.settingsRepository.update { it.copy(notifications = enabled) } }
                },
                icon = Icons.Default.Notifications,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_permission_group)) {
            PermissionPolicy.entries.forEach { policy ->
                val (title, hint) = when (policy) {
                    PermissionPolicy.ASK -> R.string.settings_permission_ask to R.string.settings_permission_ask_hint
                    PermissionPolicy.AUTO_ALLOW -> R.string.settings_permission_allow to R.string.settings_permission_allow_hint
                    PermissionPolicy.DENY -> R.string.settings_permission_deny to R.string.settings_permission_deny_hint
                }
                SettingsRadioItem(
                    title = stringResource(title),
                    subtitle = stringResource(hint),
                    selected = state.permissionPolicy == policy,
                    onClick = { viewModel.setPermissionPolicy(policy) },
                )
            }
        }
    }

    if (pickerOpen) {
        ModelPickerSheet(
            models = state.models,
            selected = state.model,
            loading = state.modelsLoading,
            error = state.modelsError?.resolve(),
            onSelect = { option ->
                viewModel.selectModel(option)
                pickerOpen = false
            },
            onReload = viewModel::loadModels,
            onManageProviders = {
                pickerOpen = false
                onOpenProviders()
            },
            onDismiss = { pickerOpen = false },
        )
    }
}
