package ai.opencode.android.ui.config

import ai.opencode.android.R
import ai.opencode.android.data.model.AgentDetail
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionAction
import ai.opencode.android.data.remote.ConfigJson
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.EmptyState
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

/** Starting points for new subagents: description, prompt and sensible permissions. */
private data class AgentTemplate(val name: String, val label: Int, val detail: AgentDetail)

private val TEMPLATES = listOf(
    AgentTemplate(
        "reviewer",
        R.string.agent_template_reviewer,
        AgentDetail(
            name = "reviewer",
            description = "Reviews code for bugs, security issues and missing tests. Use after non-trivial changes.",
            mode = "subagent",
            temperature = 0.1,
            prompt = "You are a strict senior code reviewer. Read the changed files, report concrete bugs with file:line, " +
                "security problems, race conditions and missing tests. Do not edit files.",
            permissions = mapOf("edit" to PermissionAction.DENY, "bash" to PermissionAction.ASK),
        ),
    ),
    AgentTemplate(
        "tester",
        R.string.agent_template_tester,
        AgentDetail(
            name = "tester",
            description = "Writes and runs tests for a given module, fixes failing tests it wrote.",
            mode = "subagent",
            temperature = 0.2,
            prompt = "You write focused unit tests. Find the project's test framework, add tests next to existing ones, " +
                "run them and iterate until they pass. Never weaken existing assertions.",
            permissions = mapOf("edit" to PermissionAction.ALLOW, "bash" to PermissionAction.ALLOW),
        ),
    ),
    AgentTemplate(
        "researcher",
        R.string.agent_template_researcher,
        AgentDetail(
            name = "researcher",
            description = "Read-only: explores the codebase and the web, answers with sources.",
            mode = "subagent",
            temperature = 0.3,
            prompt = "You investigate questions about the codebase and documentation. Read and search only; " +
                "answer with file paths, line numbers and links.",
            permissions = mapOf("edit" to PermissionAction.DENY, "bash" to PermissionAction.DENY, "webfetch" to PermissionAction.ALLOW),
        ),
    ),
    AgentTemplate(
        "docs",
        R.string.agent_template_docs,
        AgentDetail(
            name = "docs",
            description = "Writes and updates README, docs and code comments.",
            mode = "subagent",
            temperature = 0.4,
            prompt = "You write clear technical documentation. Keep it accurate to the code, short, with runnable examples.",
            permissions = mapOf("bash" to PermissionAction.DENY),
        ),
    ),
    AgentTemplate(
        "phone",
        R.string.agent_template_phone,
        AgentDetail(
            name = "phone",
            description = "Does things on the Android phone with the android_* tools: clipboard, notifications, apps, files in /sdcard.",
            mode = "all",
            temperature = 0.2,
            prompt = "You operate the user's Android phone through the android MCP tools and the shell. " +
                "Shared storage is at /sdcard. Confirm before deleting files.",
            permissions = mapOf("bash" to PermissionAction.ALLOW, "edit" to PermissionAction.ALLOW, "external_directory" to PermissionAction.ALLOW),
        ),
    ),
)

@Composable
fun AgentsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: AgentsViewModel = viewModel(factory = AgentsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Pair<AgentDetail, Boolean>?>(null) }
    MessageSnackbar(state.message, snackbar, viewModel::messageShown)

    Box {
        SettingsPage(title = stringResource(R.string.agents_title), onBack = onBack, snackbarHostState = snackbar) {
            Text(
                text = stringResource(R.string.agents_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            if (!state.canEdit && !state.loading) {
                Text(
                    text = stringResource(R.string.agents_read_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            when {
                state.loading && state.agents.isEmpty() -> LoadingIndicator()
                state.agents.isEmpty() -> EmptyState(title = stringResource(R.string.agents_empty), message = state.error.orEmpty())
                else -> {
                    val (subagents, primary) = state.agents.partition { it.isSubagent }
                    AgentGroup(stringResource(R.string.agents_primary), primary) { editing = it to false }
                    AgentGroup(stringResource(R.string.agents_subagents), subagents) { editing = it to false }
                }
            }
            if (state.canEdit) {
                SettingsGroup(title = stringResource(R.string.agents_templates)) {
                    TemplateRow { template -> editing = template.detail to true }
                }
            }
            Spacer(Modifier.padding(bottom = 72.dp))
        }
        if (state.canEdit) {
            ExtendedFloatingActionButton(
                onClick = { editing = AgentDetail(name = "", mode = "subagent") to true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.agents_new)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            )
        }
    }

    editing?.let { (agent, isNew) ->
        AgentEditor(
            initial = agent,
            isNew = isNew,
            tools = state.tools,
            models = state.models,
            canEdit = state.canEdit,
            saving = state.saving,
            onSave = { updated -> viewModel.save(updated) { editing = null } },
            onReset = {
                viewModel.reset(agent)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun AgentGroup(title: String, agents: List<AgentDetail>, onOpen: (AgentDetail) -> Unit) {
    if (agents.isEmpty()) return
    SettingsGroup(title = title) {
        agents.forEachIndexed { index, agent ->
            if (index > 0) SettingsDivider()
            SettingsItem(
                title = agent.name + if (agent.disabled) " (" + stringResource(R.string.agents_disabled) + ")" else "",
                subtitle = listOfNotNull(
                    agent.description,
                    agent.model?.key,
                    agent.temperature?.let { "t=" + String.format(Locale.US, "%.2f", it) },
                ).joinToString(" · ").ifBlank { null },
                icon = if (agent.isSubagent) Icons.Default.SmartToy else Icons.Default.AutoAwesome,
                showChevron = true,
                onClick = { onOpen(agent) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateRow(onPick: (AgentTemplate) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TEMPLATES.forEach { template ->
            AssistChip(onClick = { onPick(template) }, label = { Text(stringResource(template.label)) })
        }
    }
}

private val COLORS = listOf("primary", "secondary", "accent", "success", "warning", "error", "info")

/** Full-screen editor: everything opencode's `agent.<name>` config supports. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AgentEditor(
    initial: AgentDetail,
    isNew: Boolean,
    tools: List<String>,
    models: List<ModelOption>,
    canEdit: Boolean,
    saving: Boolean,
    onSave: (AgentDetail) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var agent by remember { mutableStateOf(initial) }
    var stepsText by remember { mutableStateOf(initial.steps?.toString().orEmpty()) }
    var modelPicker by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (isNew) stringResource(R.string.agents_new) else agent.name) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(
                            enabled = canEdit && !saving && AgentsViewModel.NAME.matches(agent.name),
                            onClick = { onSave(agent.copy(steps = stepsText.toIntOrNull()?.takeIf { it > 0 })) },
                        ) { Text(stringResource(R.string.action_save)) }
                    },
                )
            },
            modifier = Modifier.fillMaxSize().imePadding(),
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isNew) {
                    OutlinedTextField(
                        value = agent.name,
                        onValueChange = { agent = agent.copy(name = it.trim()) },
                        label = { Text(stringResource(R.string.agents_name)) },
                        supportingText = { Text(stringResource(R.string.agents_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = agent.description.orEmpty(),
                    onValueChange = { agent = agent.copy(description = it) },
                    label = { Text(stringResource(R.string.agents_description)) },
                    supportingText = { Text(stringResource(R.string.agents_description_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Label(stringResource(R.string.agents_mode))
                val modes = listOf("primary", "subagent", "all")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = agent.mode == mode,
                            onClick = { agent = agent.copy(mode = mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(stringResource(modeLabel(mode))) }
                    }
                }

                Label(stringResource(R.string.agents_model))
                Row(
                    Modifier.fillMaxWidth().clickable { modelPicker = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = agent.model?.key ?: stringResource(R.string.agents_model_inherit),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { modelPicker = true }) { Text(stringResource(R.string.action_edit)) }
                }

                OptionalSlider(
                    title = stringResource(R.string.agents_temperature),
                    hint = stringResource(R.string.agents_temperature_hint),
                    value = agent.temperature,
                    range = 0f..2f,
                    onChange = { agent = agent.copy(temperature = it) },
                )
                OptionalSlider(
                    title = stringResource(R.string.agents_top_p),
                    hint = stringResource(R.string.agents_top_p_hint),
                    value = agent.topP,
                    range = 0f..1f,
                    onChange = { agent = agent.copy(topP = it) },
                )
                OutlinedTextField(
                    value = stepsText,
                    onValueChange = { stepsText = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.agents_steps)) },
                    supportingText = { Text(stringResource(R.string.agents_steps_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = agent.prompt.orEmpty(),
                    onValueChange = { agent = agent.copy(prompt = it) },
                    label = { Text(stringResource(R.string.agents_prompt)) },
                    supportingText = { Text(stringResource(R.string.agents_prompt_hint)) },
                    minLines = 5,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )

                Label(stringResource(R.string.agents_color))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    COLORS.forEach { color ->
                        FilterChip(
                            selected = agent.color == color,
                            onClick = { agent = agent.copy(color = if (agent.color == color) null else color) },
                            label = { Text(color) },
                        )
                    }
                }

                HorizontalDivider()
                Label(stringResource(R.string.agents_permissions))
                Text(stringResource(R.string.agents_permissions_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ConfigJson.PERMISSION_KEYS.forEach { key ->
                    PermissionRow(
                        key = key,
                        value = agent.permissions[key],
                        onChange = { action ->
                            agent = agent.copy(permissions = if (action == null) agent.permissions - key else agent.permissions + (key to action))
                        },
                    )
                }

                if (tools.isNotEmpty()) {
                    HorizontalDivider()
                    Label(stringResource(R.string.agents_tools))
                    Text(stringResource(R.string.agents_tools_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        tools.forEach { tool ->
                            val enabled = agent.tools[tool] != false
                            FilterChip(
                                selected = enabled,
                                onClick = {
                                    agent = agent.copy(tools = if (enabled) agent.tools + (tool to false) else agent.tools - tool)
                                },
                                label = { Text(tool, fontFamily = FontFamily.Monospace) },
                            )
                        }
                    }
                }

                HorizontalDivider()
                SwitchLine(stringResource(R.string.agents_hidden), stringResource(R.string.agents_hidden_hint), agent.hidden) {
                    agent = agent.copy(hidden = it)
                }
                SwitchLine(stringResource(R.string.agents_disable), stringResource(R.string.agents_disable_hint), agent.disabled) {
                    agent = agent.copy(disabled = it)
                }
                if (!isNew) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.agents_reset), color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.padding(bottom = 24.dp))
            }
        }
    }

    if (modelPicker) {
        ModelPickerSheet(
            models = models,
            selected = agent.model,
            loading = false,
            error = null,
            onSelect = { option ->
                agent = agent.copy(model = option?.let { ModelRef(it.providerID, it.modelID) })
                modelPicker = false
            },
            onReload = {},
            onManageProviders = { modelPicker = false },
            onDismiss = { modelPicker = false },
        )
    }
}

private fun modeLabel(mode: String): Int = when (mode) {
    "primary" -> R.string.agents_mode_primary
    "subagent" -> R.string.agents_mode_subagent
    else -> R.string.agents_mode_all
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** A slider that can be switched back to "server default" (null). */
@Composable
private fun OptionalSlider(title: String, hint: String, value: Double?, range: ClosedFloatingPointRange<Float>, onChange: (Double?) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = value?.let { String.format(Locale.US, "%.2f", it) } ?: stringResource(R.string.agents_default),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Switch(checked = value != null, onCheckedChange = { on -> onChange(if (on) (range.start + range.endInclusive) / 4.0 else null) })
        }
        if (value != null) {
            Slider(
                value = value.toFloat().coerceIn(range),
                onValueChange = { onChange((it * 100).toInt() / 100.0) },
                valueRange = range,
            )
        }
    }
}

/** default / ask / allow / deny for one permission key. */
@Composable
internal fun PermissionRow(key: String, value: PermissionAction?, onChange: (PermissionAction?) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(key, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        val options = listOf(null, PermissionAction.ASK, PermissionAction.ALLOW, PermissionAction.DENY)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = value == option,
                    onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(
                        text = stringResource(
                            when (option) {
                                null -> R.string.agents_default
                                PermissionAction.ASK -> R.string.perm_ask
                                PermissionAction.ALLOW -> R.string.perm_allow
                                PermissionAction.DENY -> R.string.perm_deny
                            },
                        ),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
