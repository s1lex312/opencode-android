package ai.opencode.android.ui.models

import ai.opencode.android.R
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.ui.components.EmptyState
import ai.opencode.android.ui.components.LoadingIndicator
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom sheet for picking the model used by new prompts. Shared by the chat composer and
 * Settings → Agent & model. `onSelect(null)` means "use the server's default model".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<ModelOption>,
    selected: ModelRef?,
    loading: Boolean,
    error: String?,
    onSelect: (ModelOption?) -> Unit,
    onReload: () -> Unit,
    onManageProviders: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    val grouped = remember(models, query) {
        val needle = query.trim()
        models
            .filter {
                needle.isBlank() || it.modelID.contains(needle, true) ||
                    it.name.contains(needle, true) || it.providerID.contains(needle, true)
            }
            .groupBy { it.providerID }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.models_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onReload, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_reload))
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.models_search_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when {
                loading && models.isEmpty() -> LoadingIndicator()
                models.isEmpty() -> EmptyState(
                    title = stringResource(R.string.models_empty_title),
                    message = error ?: stringResource(R.string.models_empty_message),
                    icon = Icons.Default.Hub,
                    actionLabel = stringResource(R.string.models_manage_providers),
                    onAction = onManageProviders,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    if (query.isBlank()) {
                        item(key = "server-default") {
                            ModelRow(
                                title = stringResource(R.string.models_server_default),
                                subtitle = stringResource(R.string.models_server_default_hint),
                                selected = selected == null,
                                enabled = true,
                                leading = true,
                                onClick = { onSelect(null) },
                            )
                        }
                    }
                    if (grouped.isEmpty()) {
                        item(key = "no-match") {
                            Text(
                                text = stringResource(R.string.models_no_match, query.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp),
                            )
                        }
                    }
                    grouped.forEach { (provider, options) ->
                        item(key = "header-$provider") {
                            Text(
                                text = provider,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(options, key = { it.key }) { option ->
                            val disabledLabel = stringResource(R.string.models_disabled)
                            ModelRow(
                                title = option.name.ifBlank { option.modelID },
                                subtitle = when {
                                    option.disabled -> "${option.modelID} · $disabledLabel"
                                    option.modelID != option.name -> option.modelID
                                    else -> null
                                },
                                selected = selected?.providerID == option.providerID && selected.modelID == option.modelID,
                                enabled = !option.disabled,
                                leading = false,
                                onClick = { onSelect(option) },
                            )
                        }
                    }
                }
            }
            if (models.isNotEmpty()) {
                OutlinedButton(
                    onClick = onManageProviders,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Hub, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.models_manage_providers))
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    leading: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.models_selected),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
