package ai.opencode.android.ui.tools

import ai.opencode.android.R
import ai.opencode.android.data.hooks.HookRun
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.Hook
import ai.opencode.android.settings.HookEvent
import ai.opencode.android.settings.Workbench
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsRadioItem
import ai.opencode.android.ui.components.SwitchRow
import ai.opencode.android.ui.settings.SettingsPage
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun hookEventLabel(event: HookEvent): String = stringResource(
    when (event) {
        HookEvent.PROMPT_SUBMIT -> R.string.hooks_event_prompt
        HookEvent.BEFORE_TOOL -> R.string.hooks_event_before_tool
        HookEvent.FILE_EDITED -> R.string.hooks_event_file
        HookEvent.TURN_END -> R.string.hooks_event_turn
    },
)

@Composable
private fun hookEventHint(event: HookEvent): String = stringResource(
    when (event) {
        HookEvent.PROMPT_SUBMIT -> R.string.hooks_event_prompt_hint
        HookEvent.BEFORE_TOOL -> R.string.hooks_event_before_tool_hint
        HookEvent.FILE_EDITED -> R.string.hooks_event_file_hint
        HookEvent.TURN_END -> R.string.hooks_event_turn_hint
    },
)

/** Claude Code style hooks: shell commands the app runs around the agent's work. */
@Composable
fun HooksScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: WorkbenchViewModel = viewModel(factory = WorkbenchViewModel.factory(container))
    val hooks by viewModel.hooks.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Hook?>(null) }

    SettingsPage(title = stringResource(R.string.hooks_title), onBack = onBack) {
        Text(
            stringResource(R.string.hooks_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        HookEvent.entries.forEach { event ->
            val ofEvent = hooks.filter { it.event == event }
            SettingsGroup(title = hookEventLabel(event)) {
                Text(
                    hookEventHint(event),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ofEvent.forEach { hook ->
                    SettingsDivider()
                    SettingsItem(
                        title = hook.name,
                        subtitle = (if (hook.matcher.isNotBlank()) "[${hook.matcher}] " else "") + hook.command,
                        icon = Icons.Default.Webhook,
                        onClick = { editing = hook },
                        trailing = {
                            if (testing == hook.id) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = { viewModel.test(hook) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.hooks_test))
                                }
                            }
                            Switch(checked = hook.enabled, onCheckedChange = { viewModel.setHookEnabled(hook, it) })
                        },
                    )
                }
                SettingsDivider()
                SettingsItem(
                    title = stringResource(R.string.hooks_add),
                    icon = Icons.Default.Add,
                    onClick = { editing = Hook(id = Workbench.newId(), name = "", event = event, command = "") },
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.hooks_templates)) {
            HOOK_TEMPLATES.forEachIndexed { index, template ->
                if (index > 0) SettingsDivider()
                SettingsItem(
                    title = template.name,
                    subtitle = hookEventLabel(template.event) + " · " + template.command,
                    icon = Icons.Default.Add,
                    onClick = { editing = template.copy(id = Workbench.newId()) },
                )
            }
        }
        if (runs.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.hooks_runs)) {
                runs.forEachIndexed { index, run ->
                    if (index > 0) SettingsDivider()
                    HookRunRow(run)
                }
            }
        }
    }

    editing?.let { hook ->
        HookEditor(
            initial = hook,
            exists = hooks.any { it.id == hook.id },
            onSave = {
                viewModel.saveHook(it)
                editing = null
            },
            onDelete = {
                viewModel.deleteHook(hook.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun HookRunRow(run: HookRun) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when {
                    run.timedOut -> "⏱"
                    run.ok -> "✓"
                    else -> "✗ ${run.exitCode ?: "?"}"
                },
                fontFamily = FontFamily.Monospace,
                color = if (run.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                run.hookName + (run.target?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("${run.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (open || !run.ok) {
            Text(
                run.output.ifBlank { stringResource(R.string.hooks_no_output) }.takeLast(3000),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                maxLines = if (open) 60 else 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HookEditor(initial: Hook, exists: Boolean, onSave: (Hook) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var event by remember { mutableStateOf(initial.event) }
    var matcher by remember { mutableStateOf(initial.matcher) }
    var command by remember { mutableStateOf(initial.command) }
    var timeout by remember { mutableStateOf(initial.timeoutSeconds.toString()) }
    var feedback by remember { mutableStateOf(initial.feedback) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hooks_editor)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.hooks_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                HookEvent.entries.forEach { option ->
                    SettingsRadioItem(title = hookEventLabel(option), selected = event == option, onClick = { event = option })
                }
                if (event == HookEvent.FILE_EDITED || event == HookEvent.BEFORE_TOOL) {
                    OutlinedTextField(
                        value = matcher,
                        onValueChange = { matcher = it },
                        label = { Text(stringResource(if (event == HookEvent.FILE_EDITED) R.string.hooks_matcher_glob else R.string.hooks_matcher_tool)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.hooks_command)) },
                    supportingText = { Text(stringResource(R.string.hooks_command_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { value -> timeout = value.filter { it.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.hooks_timeout)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    title = stringResource(if (event == HookEvent.PROMPT_SUBMIT) R.string.hooks_feedback_prompt else R.string.hooks_feedback),
                    subtitle = stringResource(if (event == HookEvent.PROMPT_SUBMIT) R.string.hooks_feedback_prompt_hint else R.string.hooks_feedback_hint),
                    checked = feedback,
                    onCheckedChange = { feedback = it },
                )
                SwitchRow(title = stringResource(R.string.hooks_enabled), checked = enabled, onCheckedChange = { enabled = it })
                if (exists) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { command.trim().take(40) },
                            event = event,
                            matcher = matcher.trim(),
                            command = command.trim(),
                            timeoutSeconds = timeout.toIntOrNull()?.coerceIn(1, 3600) ?: 60,
                            feedback = feedback,
                            enabled = enabled,
                        ),
                    )
                },
                enabled = command.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val HOOK_TEMPLATES = listOf(
    Hook("", "Prettier", HookEvent.FILE_EDITED, "npx --yes prettier --write \"\$FILE\"", matcher = "*.{js,jsx,ts,tsx,json,css,md}"),
    Hook("", "ktlint", HookEvent.FILE_EDITED, "ktlint -F \"\$FILE\"", matcher = "*.{kt,kts}", feedback = true),
    Hook("", "ruff", HookEvent.FILE_EDITED, "ruff check --fix \"\$FILE\" && ruff format \"\$FILE\"", matcher = "*.py", feedback = true),
    Hook("", "gofmt", HookEvent.FILE_EDITED, "gofmt -w \"\$FILE\"", matcher = "*.go"),
    Hook("", "Tests after each turn", HookEvent.TURN_END, "npm test --silent 2>&1 | tail -n 60", timeoutSeconds = 600, feedback = true),
    Hook("", "Type check after each turn", HookEvent.TURN_END, "npx --yes tsc --noEmit 2>&1 | head -n 80", timeoutSeconds = 300, feedback = true),
    Hook("", "Block rm -rf and force pushes", HookEvent.BEFORE_TOOL, "echo \"\$TOOL_PATTERNS\" | grep -Eq 'rm -rf|git push (-f|--force)' && exit 2 || exit 1", matcher = "bash"),
    Hook("", "Allow read-only git", HookEvent.BEFORE_TOOL, "echo \"\$TOOL_PATTERNS\" | grep -Eq '^git (status|diff|log|show)' && exit 0 || exit 1", matcher = "bash"),
    Hook("", "Add git status to prompts", HookEvent.PROMPT_SUBMIT, "git status --short --branch 2>/dev/null | head -n 40", feedback = true),
)
