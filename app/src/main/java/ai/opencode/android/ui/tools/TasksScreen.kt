package ai.opencode.android.ui.tools

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.TaskStatus
import ai.opencode.android.ui.components.SettingsGroup
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

/** Background tasks: run a prompt in a fresh chat and get a notification with the answer. */
@Composable
fun TasksScreen(container: AppContainer, onBack: () -> Unit, onOpenSession: (String) -> Unit, initialPrompt: String = "") {
    val viewModel: TasksViewModel = viewModel(factory = TasksViewModel.factory(container))
    val tasks by viewModel.list.collectAsStateWithLifecycle()
    val starting by viewModel.starting.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var prompt by rememberSaveable { mutableStateOf(initialPrompt) }
    val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    SettingsPage(title = stringResource(R.string.tasks_title), onBack = onBack) {
        Text(
            stringResource(R.string.tasks_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.tasks_prompt)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = { viewModel.launch(prompt) { prompt = "" } }, enabled = prompt.isNotBlank() && !starting) {
                if (starting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tasks_run))
            }
        }
        if (tasks.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tasks_recent),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (tasks.any { it.status != TaskStatus.RUNNING }) {
                    TextButton(onClick = { viewModel.clearFinished() }) { Text(stringResource(R.string.tasks_clear)) }
                }
            }
            SettingsGroup {
                tasks.forEachIndexed { index, task ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenSession(task.sessionId) }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        when (task.status) {
                            TaskStatus.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp).padding(top = 2.dp), strokeWidth = 2.dp)
                            TaskStatus.DONE -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            TaskStatus.FAILED -> Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(task.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val detail = when (task.status) {
                                TaskStatus.RUNNING -> stringResource(R.string.tasks_running)
                                TaskStatus.DONE -> task.result ?: stringResource(R.string.task_notify_empty)
                                TaskStatus.FAILED -> task.error ?: stringResource(R.string.tasks_failed)
                            }
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                format.format(Date(task.finishedAt ?: task.createdAt)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        IconButton(onClick = { viewModel.remove(task.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
    }
}
