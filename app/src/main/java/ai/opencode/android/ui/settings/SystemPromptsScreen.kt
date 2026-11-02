package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.AppSettings
import ai.opencode.android.settings.SystemPromptProfile
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.format.oneLine
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * User-written system prompts, switchable like configs. The active one is added to every prompt
 * on top of the agent's own instructions; "None" sends only the agent's prompt.
 */
@Composable
fun SystemPromptsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<SystemPromptProfile?>(null) }
    val update: ((AppSettings) -> AppSettings) -> Unit = { transform ->
        scope.launch { container.settingsRepository.update(transform) }
    }

    SettingsPage(title = stringResource(R.string.system_prompts_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.system_prompts_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )

        SettingsGroup(title = stringResource(R.string.system_prompts_active)) {
            PromptRow(
                title = stringResource(R.string.chat_system_prompt_none),
                subtitle = stringResource(R.string.system_prompts_none_hint),
                selected = settings.activeSystemPromptId == null,
                onSelect = { update { it.copy(activeSystemPromptId = null) } },
                onEdit = null,
            )
            settings.systemPrompts.forEach { prompt ->
                SettingsDivider()
                PromptRow(
                    title = prompt.name.ifBlank { stringResource(R.string.system_prompt_untitled) },
                    subtitle = prompt.text.oneLine(90),
                    selected = settings.activeSystemPromptId == prompt.id,
                    onSelect = { update { it.copy(activeSystemPromptId = prompt.id) } },
                    onEdit = { editing = prompt },
                )
            }
        }

        Button(
            onClick = { editing = SystemPromptProfile(id = UUID.randomUUID().toString(), name = "", text = "") },
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.system_prompts_add))
        }
    }

    editing?.let { prompt ->
        val isNew = settings.systemPrompts.none { it.id == prompt.id }
        PromptEditor(
            initial = prompt,
            isNew = isNew,
            onSave = { saved ->
                editing = null
                update { current ->
                    val list = if (isNew) current.systemPrompts + saved else current.systemPrompts.map { if (it.id == saved.id) saved else it }
                    // A freshly written prompt is what the user wants to use next.
                    current.copy(systemPrompts = list, activeSystemPromptId = if (isNew) saved.id else current.activeSystemPromptId)
                }
            },
            onDelete = {
                editing = null
                update { current ->
                    current.copy(
                        systemPrompts = current.systemPrompts.filterNot { it.id == prompt.id },
                        activeSystemPromptId = current.activeSystemPromptId.takeUnless { it == prompt.id },
                    )
                }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun PromptRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit, onEdit: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
            }
        }
    }
}

private data class PromptTemplate(val nameRes: Int, val textRes: Int)

private val templates = listOf(
    PromptTemplate(R.string.system_template_concise, R.string.system_template_concise_text),
    PromptTemplate(R.string.system_template_reviewer, R.string.system_template_reviewer_text),
    PromptTemplate(R.string.system_template_teacher, R.string.system_template_teacher_text),
    PromptTemplate(R.string.system_template_russian, R.string.system_template_russian_text),
)

@Composable
private fun PromptEditor(
    initial: SystemPromptProfile,
    isNew: Boolean,
    onSave: (SystemPromptProfile) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var text by remember { mutableStateOf(initial.text) }
    val templateNames = templates.map { stringResource(it.nameRes) }
    val templateTexts = templates.map { stringResource(it.textRes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.system_prompts_new else R.string.system_prompts_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.system_prompts_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.system_prompts_text)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
                if (text.isBlank()) {
                    Text(stringResource(R.string.system_prompts_templates), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        templateNames.forEachIndexed { index, label ->
                            SuggestionChip(
                                onClick = {
                                    if (name.isBlank()) name = label
                                    text = templateTexts[index]
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(name = name.trim(), text = text.trim())) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
