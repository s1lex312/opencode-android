package ai.opencode.android.ui.config

import ai.opencode.android.R
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionAction
import ai.opencode.android.data.remote.ConfigJson
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.models.ModelPickerSheet
import ai.opencode.android.ui.settings.MessageSnackbar
import ai.opencode.android.ui.settings.SettingsPage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Permission presets, from cautious to "the agent may do anything on this phone". */
private enum class Preset(val label: Int, val hint: Int, val map: Map<String, PermissionAction>) {
    CAUTIOUS(
        R.string.behaviour_preset_cautious,
        R.string.behaviour_preset_cautious_hint,
        ConfigJson.PERMISSION_KEYS.associateWith { PermissionAction.ASK } +
            listOf("read", "glob", "grep", "list", "lsp", "todowrite", "question").associateWith { PermissionAction.ALLOW },
    ),
    BALANCED(
        R.string.behaviour_preset_balanced,
        R.string.behaviour_preset_balanced_hint,
        ConfigJson.PERMISSION_KEYS.associateWith { PermissionAction.ALLOW } +
            mapOf("bash" to PermissionAction.ASK, "external_directory" to PermissionAction.ASK, "doom_loop" to PermissionAction.ASK),
    ),
    FULL(
        R.string.behaviour_preset_full,
        R.string.behaviour_preset_full_hint,
        ConfigJson.PERMISSION_KEYS.associateWith { PermissionAction.ALLOW },
    ),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BehaviourScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: BehaviourViewModel = viewModel(factory = BehaviourViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var smallModelPicker by remember { mutableStateOf(false) }
    MessageSnackbar(state.message, snackbar, viewModel::messageShown)
    val config = state.config

    Box {
        SettingsPage(title = stringResource(R.string.behaviour_title), onBack = onBack, snackbarHostState = snackbar) {
            Text(
                text = stringResource(if (state.canEdit || state.loading) R.string.behaviour_intro else R.string.behaviour_read_only),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.canEdit || state.loading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            if (state.loading) {
                LoadingIndicator()
                return@SettingsPage
            }
            if (!state.canEdit) return@SettingsPage

            SettingsGroup(title = stringResource(R.string.behaviour_presets)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Preset.entries.forEach { preset ->
                        val active = ConfigJson.PERMISSION_KEYS.all { key -> config.permissions[key] == preset.map[key] }
                        Row(
                            Modifier.fillMaxWidth().clickable { viewModel.edit { it.copy(permissions = preset.map) } }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = active,
                                onClick = { viewModel.edit { it.copy(permissions = preset.map) } },
                                label = { Text(stringResource(preset.label)) },
                            )
                            Spacer(Modifier.padding(start = 10.dp))
                            Text(
                                text = stringResource(preset.hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SettingsGroup(title = stringResource(R.string.behaviour_permissions)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ConfigJson.PERMISSION_KEYS.forEach { key ->
                        PermissionRow(
                            key = key,
                            value = config.permissions[key],
                            onChange = { action ->
                                viewModel.edit { it.copy(permissions = if (action == null) it.permissions - key else it.permissions + (key to action)) }
                            },
                        )
                    }
                }
            }

            if (state.tools.isNotEmpty()) {
                SettingsGroup(title = stringResource(R.string.behaviour_tools)) {
                    Text(
                        text = stringResource(R.string.behaviour_tools_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                    FlowRow(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        state.tools.forEach { tool ->
                            val enabled = config.tools[tool] != false
                            FilterChip(
                                selected = enabled,
                                onClick = { viewModel.edit { it.copy(tools = if (enabled) it.tools + (tool to false) else it.tools + (tool to true)) } },
                                label = { Text(tool, fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                }
            }

            SettingsGroup(title = stringResource(R.string.behaviour_context)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SwitchLine(stringResource(R.string.behaviour_auto_compact), stringResource(R.string.behaviour_auto_compact_hint), config.autoCompact != false) { on ->
                        viewModel.edit { it.copy(autoCompact = on) }
                    }
                    SwitchLine(stringResource(R.string.behaviour_prune), stringResource(R.string.behaviour_prune_hint), config.prune == true) { on ->
                        viewModel.edit { it.copy(prune = on) }
                    }
                    NumberField(stringResource(R.string.behaviour_reserved), stringResource(R.string.behaviour_reserved_hint), config.reservedTokens) { value ->
                        viewModel.edit { it.copy(reservedTokens = value) }
                    }
                }
            }

            SettingsGroup(title = stringResource(R.string.behaviour_general)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SwitchLine(stringResource(R.string.behaviour_snapshot), stringResource(R.string.behaviour_snapshot_hint), config.snapshot != false) { on ->
                        viewModel.edit { it.copy(snapshot = on) }
                    }
                    Text(stringResource(R.string.behaviour_share), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("manual" to R.string.behaviour_share_manual, "auto" to R.string.behaviour_share_auto, "disabled" to R.string.behaviour_share_disabled)
                            .forEach { (value, label) ->
                                FilterChip(
                                    selected = (config.share ?: "manual") == value,
                                    onClick = { viewModel.edit { it.copy(share = value) } },
                                    label = { Text(stringResource(label)) },
                                )
                            }
                    }
                    Row(Modifier.fillMaxWidth().clickable { smallModelPicker = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.behaviour_small_model), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = config.smallModel ?: stringResource(R.string.agents_default),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(stringResource(R.string.behaviour_small_model_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    DefaultAgentPicker(config.defaultAgent, state.agents) { name -> viewModel.edit { it.copy(defaultAgent = name) } }
                    NumberField(stringResource(R.string.behaviour_subagent_depth), stringResource(R.string.behaviour_subagent_depth_hint), config.subagentDepth) { value ->
                        viewModel.edit { it.copy(subagentDepth = value) }
                    }
                    OutlinedTextField(
                        value = config.instructions.joinToString("\n"),
                        onValueChange = { text -> viewModel.edit { it.copy(instructions = text.lines().map(String::trim).filter(String::isNotEmpty)) } },
                        label = { Text(stringResource(R.string.behaviour_instructions)) },
                        supportingText = { Text(stringResource(R.string.behaviour_instructions_hint)) },
                        placeholder = { Text("AGENTS.md\n~/.config/opencode/rules/*.md") },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = config.username.orEmpty(),
                        onValueChange = { text -> viewModel.edit { it.copy(username = text) } },
                        label = { Text(stringResource(R.string.behaviour_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text(
                text = stringResource(R.string.behaviour_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
            )
            Spacer(Modifier.padding(bottom = 72.dp))
        }
        if (state.dirty) {
            ExtendedFloatingActionButton(
                onClick = viewModel::save,
                icon = { Icon(Icons.Default.Save, contentDescription = null) },
                text = { Text(stringResource(if (state.saving) R.string.behaviour_saving else R.string.action_save)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            )
        }
    }

    if (smallModelPicker) {
        ModelPickerSheet(
            models = state.models,
            selected = config.smallModel?.let(ConfigJson::parseModel),
            loading = false,
            error = null,
            onSelect = { option ->
                viewModel.edit { it.copy(smallModel = option?.let { o -> ModelRef(o.providerID, o.modelID).key }) }
                smallModelPicker = false
            },
            onReload = {},
            onManageProviders = { smallModelPicker = false },
            onDismiss = { smallModelPicker = false },
        )
    }
}

@Composable
private fun DefaultAgentPicker(current: String?, agents: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.behaviour_default_agent), style = MaterialTheme.typography.bodyLarge)
                Text(current ?: "build", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            agents.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    open = false
                    onPick(name)
                })
            }
        }
    }
}

@Composable
private fun NumberField(title: String, hint: String, value: Int?, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { text -> onChange(text.filter(Char::isDigit).take(7).toIntOrNull()) },
        label = { Text(title) },
        supportingText = { Text(hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
