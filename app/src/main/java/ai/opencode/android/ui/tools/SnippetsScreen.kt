package ai.opencode.android.ui.tools

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.Snippet
import ai.opencode.android.settings.Workbench
import ai.opencode.android.ui.chat.ClientCommand
import ai.opencode.android.ui.chat.Snippets
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SwitchRow
import ai.opencode.android.ui.settings.SettingsPage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Saved prompts, run in any chat as `/name args`. */
@Composable
fun SnippetsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: WorkbenchViewModel = viewModel(factory = WorkbenchViewModel.factory(container))
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Snippet?>(null) }

    SettingsPage(title = stringResource(R.string.snippets_title), onBack = onBack) {
        Text(
            stringResource(R.string.snippets_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        SettingsGroup {
            snippets.forEachIndexed { index, snippet ->
                if (index > 0) SettingsDivider()
                SettingsItem(
                    title = "/" + snippet.name,
                    subtitle = snippet.description.ifBlank { snippet.body.lineSequence().first().take(100) },
                    icon = Icons.Default.Bolt,
                    onClick = { editing = snippet },
                    trailing = {
                        IconButton(onClick = { viewModel.deleteSnippet(snippet.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    },
                )
            }
            if (snippets.isNotEmpty()) SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.snippets_add),
                icon = Icons.Default.Add,
                onClick = { editing = Snippet(id = Workbench.newId(), name = "", body = "") },
            )
        }
        SettingsGroup(title = stringResource(R.string.snippets_examples)) {
            SNIPPET_TEMPLATES.forEachIndexed { index, template ->
                if (index > 0) SettingsDivider()
                SettingsItem(
                    title = "/" + template.name,
                    subtitle = template.description,
                    icon = Icons.Default.Add,
                    enabled = snippets.none { it.name == template.name },
                    onClick = { editing = template.copy(id = Workbench.newId()) },
                )
            }
        }
    }

    editing?.let { snippet ->
        SnippetEditor(
            initial = snippet,
            taken = snippets.filter { it.id != snippet.id }.map { it.name }.toSet(),
            onSave = {
                viewModel.saveSnippet(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun SnippetEditor(initial: Snippet, taken: Set<String>, onSave: (Snippet) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var body by remember { mutableStateOf(initial.body) }
    var send by remember { mutableStateOf(initial.send) }
    val normalized = Snippets.normalizeName(name)
    val nameError = when {
        normalized.isEmpty() -> null
        !Snippets.isValidName(normalized) -> stringResource(R.string.snippets_name_invalid)
        normalized in taken -> stringResource(R.string.snippets_name_taken)
        ClientCommand.find(normalized) != null -> stringResource(R.string.snippets_name_builtin)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.snippets_editor)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.snippets_name)) },
                    prefix = { Text("/", fontFamily = FontFamily.Monospace) },
                    isError = nameError != null,
                    supportingText = { Text(nameError ?: "/$normalized") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.snippets_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text(stringResource(R.string.snippets_body)) },
                    supportingText = { Text(stringResource(R.string.snippets_body_hint)) },
                    minLines = 4,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    title = stringResource(R.string.snippets_send),
                    subtitle = stringResource(R.string.snippets_send_hint),
                    checked = send,
                    onCheckedChange = { send = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(name = normalized, description = description.trim(), body = body.trim(), send = send)) },
                enabled = normalized.isNotEmpty() && nameError == null && body.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** One-tap starting points; the user can edit them before saving. */
private val SNIPPET_TEMPLATES = listOf(
    Snippet("", "explain", "Explain \$ARGUMENTS: what it does, how it fits into the project, and anything surprising.", "Explain a file or symbol"),
    Snippet("", "tests", "Write tests for \$ARGUMENTS. Cover edge cases, run them, and fix what fails.", "Write and run tests"),
    Snippet("", "fix", "Find and fix the bug: \$ARGUMENTS. Reproduce it first, then fix the root cause and verify.", "Fix a bug end to end"),
    Snippet("", "review", "Review the uncommitted changes (git diff). List bugs, risky spots and missing tests, most severe first.", "Review the working tree"),
    Snippet("", "commit-msg", "Write a one-line imperative commit message for the staged changes (git diff --cached). Output only the message.", "Commit message for staged changes"),
    Snippet("", "refactor", "Refactor \$ARGUMENTS for readability without changing behaviour. Keep the public API, run the tests.", "Behaviour-preserving refactor"),
)
