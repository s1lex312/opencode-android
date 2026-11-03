package ai.opencode.android.ui.providers

import ai.opencode.android.R
import ai.opencode.android.data.providers.ModelTest
import ai.opencode.android.data.providers.RemoteModel
import ai.opencode.android.ui.theme.StatusColors
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * "Models from the endpoint": fetch `GET /models`, pick models, send the picked ones a one-line
 * prompt and keep the ones that answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoteModelsSection(
    state: CustomProviderUiState,
    canFetch: Boolean,
    onFetch: () -> Unit,
    onToggle: (String) -> Unit,
    onSelect: (Collection<String>) -> Unit,
    onSelectWorking: () -> Unit,
    onTest: (Collection<String>) -> Unit,
    onStop: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.providers_probe_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.providers_probe_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onFetch, enabled = canFetch && !state.fetching) {
            if (state.fetching) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(if (state.remoteModels.isEmpty()) R.string.providers_probe_fetch else R.string.providers_probe_refetch))
        }
        state.fetchError?.let { error ->
            Text(
                if (error.isBlank()) stringResource(R.string.providers_probe_empty) else stringResource(R.string.providers_probe_fetch_failed, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.remoteModels.isEmpty()) return@Column

        val needle = query.trim()
        val visible = state.remoteModels.filter { needle.isEmpty() || it.id.contains(needle, ignoreCase = true) }
        val tested = state.tests.filterKeys { it in state.selected }
        Text(
            stringResource(
                R.string.providers_probe_counts,
                state.remoteModels.size,
                state.selected.size,
                tested.count { it.value.ok },
                tested.count { !it.value.ok },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.remoteModels.size > SEARCH_FROM) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.models_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onSelect(state.selected + visible.map { it.id }) },
                label = { Text(stringResource(if (needle.isEmpty()) R.string.providers_probe_all else R.string.providers_probe_all_found)) },
            )
            AssistChip(onClick = { onSelect(state.selected - visible.map { it.id }.toSet()) }, label = { Text(stringResource(R.string.providers_probe_none)) })
            if (state.testing.isEmpty()) {
                AssistChip(
                    enabled = state.selected.isNotEmpty(),
                    onClick = { onTest(state.remoteModels.map { it.id }.filter { it in state.selected }) },
                    label = { Text(stringResource(R.string.providers_probe_test_selected, state.selected.size)) },
                )
            } else {
                AssistChip(onClick = onStop, label = { Text(stringResource(R.string.providers_probe_stop, state.testing.size)) })
            }
            if (tested.isNotEmpty() && tested.any { !it.value.ok }) {
                AssistChip(onClick = onSelectWorking, label = { Text(stringResource(R.string.providers_probe_keep_working)) })
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                visible.take(MAX_ROWS).forEach { model ->
                    ModelRow(
                        model = model,
                        selected = model.id in state.selected,
                        testing = model.id in state.testing,
                        test = state.tests[model.id],
                        onToggle = { onToggle(model.id) },
                        onTest = { onTest(listOf(model.id)) },
                    )
                }
                if (visible.size > MAX_ROWS) {
                    Text(
                        stringResource(R.string.providers_probe_more, visible.size - MAX_ROWS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: RemoteModel, selected: Boolean, testing: Boolean, test: ModelTest?, onToggle: () -> Unit, onTest: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(model.id, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                model.ownedBy,
                model.contextLength?.let { stringResource(R.string.providers_probe_context, formatTokens(it)) },
            )
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (test != null) TestResult(test)
        }
        when {
            testing -> CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
            else -> IconButton(onClick = onTest) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.providers_probe_test_one))
            }
        }
    }
}

/** A manually typed model's result, under the text field. */
@Composable
internal fun TestLine(model: String, test: ModelTest) {
    Column {
        Text(model, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        TestResult(test)
    }
}

@Composable
private fun TestResult(test: ModelTest) {
    if (test.ok) {
        Text(
            stringResource(R.string.providers_probe_ok, test.millis, test.reply.orEmpty().ifBlank { "…" }.take(60)),
            style = MaterialTheme.typography.labelSmall,
            color = StatusColors.ok,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            stringResource(R.string.providers_probe_failed, test.error.orEmpty()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

private const val SEARCH_FROM = 8
private const val MAX_ROWS = 150
