package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.SettingGroup
import ai.opencode.android.settings.SettingSpec
import ai.opencode.android.settings.SettingType
import ai.opencode.android.settings.SettingsProfile
import ai.opencode.android.settings.SettingsSchema
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsRadioItem
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.text.DateFormat
import java.util.Date

private val PrettyJson = Json { prettyPrint = true }

/**
 * Settings → All settings: every stored preference, generated from [SettingsSchema]. Search,
 * edit any value with type checks, see what differs from the default, reset one or all.
 */
@Composable
fun AllSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val repository = container.settingsRepository
    val raw by repository.raw.collectAsState(initial = null)
    val values = raw ?: return
    val defaults = remember { repository.defaultsRaw() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var showInternal by rememberSaveable { mutableStateOf(false) }
    var onlyChanged by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SettingSpec?>(null) }

    val specs = remember(values) {
        val known = SettingsSchema.all
        val unknown = values.keys.filter { key -> known.none { it.key == key } }.map { SettingsSchema.spec(it, values[it]) }
        known + unknown
    }
    val titles = specs.associate { it.key to context.getString(it.title) }
    val filtered = specs.filter { spec ->
        (showInternal || !spec.internal) &&
            (!onlyChanged || isChanged(values[spec.key], defaults[spec.key])) &&
            (query.isBlank() || spec.key.contains(query, true) || titles[spec.key].orEmpty().contains(query, true))
    }

    SettingsPage(title = stringResource(R.string.all_settings_title), onBack = onBack) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.all_settings_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = onlyChanged, onClick = { onlyChanged = !onlyChanged }, label = { Text(stringResource(R.string.all_settings_changed_only)) })
            FilterChip(selected = showInternal, onClick = { showInternal = !showInternal }, label = { Text(stringResource(R.string.all_settings_internal)) })
        }
        val changedCount = specs.count { !it.internal && isChanged(values[it.key], defaults[it.key]) }
        Text(
            text = stringResource(R.string.all_settings_summary, specs.size, changedCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SettingGroup.entries.forEach { group ->
            val rows = filtered.filter { it.group == group }
            if (rows.isNotEmpty()) {
                SettingsGroup(title = stringResource(group.title)) {
                    rows.forEachIndexed { index, spec ->
                        if (index > 0) SettingsDivider()
                        SettingRow(
                            spec = spec,
                            title = if (spec.title == R.string.schema_unknown) spec.key else titles[spec.key].orEmpty(),
                            value = values[spec.key],
                            changed = isChanged(values[spec.key], defaults[spec.key]),
                            onToggle = { checked -> scope.launch { repository.setRaw(spec.key, checked) } },
                            onClick = { editing = spec },
                        )
                    }
                }
            }
        }
    }

    editing?.let { spec ->
        SettingEditDialog(
            spec = spec,
            title = if (spec.title == R.string.schema_unknown) spec.key else stringResource(spec.title),
            value = values[spec.key],
            default = defaults[spec.key],
            onDismiss = { editing = null },
            onReset = {
                editing = null
                scope.launch { repository.setRaw(spec.key, null) }
            },
            onSave = { value ->
                editing = null
                scope.launch {
                    runCatching { repository.setRaw(spec.key, value) }
                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                    if (spec.needsRestart && container.settingsRepository.current().isLocalMode) {
                        Toast.makeText(context, R.string.all_settings_restart_hint, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }
}

private fun isChanged(value: Any?, default: Any?): Boolean = when {
    value == null -> false
    default == null -> true
    else -> value.toString() != default.toString()
}

@Composable
private fun SettingRow(
    spec: SettingSpec,
    title: String,
    value: Any?,
    changed: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (changed) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.all_settings_changed_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(spec.key, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (spec.type != SettingType.BOOL) {
                Text(
                    text = SettingsSchema.format(value).ifBlank { "—" }.replace('\n', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (spec.type == SettingType.JSON) FontFamily.Monospace else null,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (spec.type == SettingType.BOOL) {
            Switch(checked = value as? Boolean ?: false, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SettingEditDialog(
    spec: SettingSpec,
    title: String,
    value: Any?,
    default: Any?,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onSave: (Any) -> Unit,
) {
    val initial = remember(value) {
        val text = SettingsSchema.format(value)
        if (spec.type == SettingType.JSON && text.isNotBlank()) {
            runCatching { PrettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }.getOrDefault(text)
        } else {
            text
        }
    }
    var text by rememberSaveable(spec.key) { mutableStateOf(initial) }
    val parsed = spec.validate(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(spec.key, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                if (spec.type == SettingType.ENUM) {
                    spec.options.forEach { option ->
                        SettingsRadioItem(title = option, selected = text == option, onClick = { text = option })
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        isError = parsed.isFailure,
                        supportingText = { parsed.exceptionOrNull()?.message?.let { Text(it) } },
                        singleLine = spec.type != SettingType.JSON && spec.type != SettingType.STRING,
                        minLines = if (spec.type == SettingType.JSON) 6 else 1,
                        maxLines = if (spec.type == SettingType.JSON) 18 else 4,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (spec.type) {
                                SettingType.INT, SettingType.LONG -> KeyboardType.Number
                                SettingType.FLOAT -> KeyboardType.Decimal
                                else -> KeyboardType.Text
                            },
                        ),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = stringResource(R.string.all_settings_default, SettingsSchema.format(default).ifBlank { "—" }.take(200)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (spec.needsRestart) {
                    Text(stringResource(R.string.all_settings_restart_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed.isSuccess, onClick = {
                val result = parsed.getOrNull() ?: return@TextButton
                // JSON is stored compact, exactly like the app writes it.
                onSave(if (spec.type == SettingType.JSON && result is String && result.isNotBlank()) Json.parseToJsonElement(result).toString() else result)
            }) { Text(stringResource(R.string.ui_apply)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.ui_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

// ---------------------------------------------------------------------------------- profiles

/** Settings → Profiles: save, switch, rename, export and import whole settings sets. */
@Composable
fun SettingsProfilesScreen(container: AppContainer, onBack: () -> Unit) {
    val profiles = container.settingsProfiles
    val state by profiles.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SettingsProfile?>(null) }
    var applying by remember { mutableStateOf<SettingsProfile?>(null) }
    var modified by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val raw by container.settingsRepository.raw.collectAsState(initial = null)
    LaunchedEffect(raw, state) { modified = profiles.activeModified() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri != null && text != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("no stream") }.isSuccess
            Toast.makeText(context, if (ok) R.string.profiles_exported else R.string.profiles_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("no stream")
                    profiles.import(text)
                }.onSuccess { Toast.makeText(context, context.getString(R.string.profiles_imported, it), Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, R.string.profiles_import_failed, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    SettingsPage(title = stringResource(R.string.profiles_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.profiles_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (state.profiles.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.profiles_saved)) {
                state.profiles.sortedBy { it.name.lowercase() }.forEachIndexed { index, profile ->
                    if (index > 0) SettingsDivider()
                    val active = profile.id == state.activeId
                    SettingsItem(
                        title = profile.name,
                        subtitle = buildString {
                            append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(profile.updatedAt)))
                            if (profile.workbench != null) append(" · ").append(context.getString(R.string.profiles_with_workbench))
                            if (active) append(" · ").append(context.getString(if (modified) R.string.profiles_active_modified else R.string.profiles_active))
                        },
                        icon = if (active) Icons.Default.CheckCircle else Icons.Default.AccountTree,
                        onClick = { applying = profile },
                        trailing = {
                            IconButton(onClick = { scope.launch { profiles.saveCurrent(profile.name, profile.workbench != null, profile.id) } }) {
                                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.profiles_overwrite))
                            }
                            IconButton(onClick = { renaming = profile }) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.profiles_rename)) }
                            IconButton(onClick = {
                                pendingExport = profiles.export(profile.id)
                                exportLauncher.launch("${profile.name.replace(Regex("[^A-Za-z0-9._-]+"), "-")}.opencode-profile.json")
                            }) { Icon(Icons.Default.SaveAs, contentDescription = stringResource(R.string.profiles_export)) }
                            IconButton(onClick = { scope.launch { profiles.delete(profile.id) } }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.profiles_delete))
                            }
                        },
                    )
                }
            }
        }
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.profiles_save_current),
                subtitle = stringResource(R.string.profiles_save_current_hint),
                icon = Icons.Default.Save,
                onClick = { saveDialog = true },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.profiles_import),
                icon = Icons.Default.FileOpen,
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
        }
    }

    if (saveDialog) {
        var name by rememberSaveable { mutableStateOf("") }
        var withWorkbench by rememberSaveable { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { saveDialog = false },
            title = { Text(stringResource(R.string.profiles_save_current)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.profiles_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { withWorkbench = !withWorkbench }) {
                        Checkbox(checked = withWorkbench, onCheckedChange = { withWorkbench = it })
                        Text(stringResource(R.string.profiles_include_workbench), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    saveDialog = false
                    scope.launch { profiles.saveCurrent(name, withWorkbench) }
                }) { Text(stringResource(R.string.ui_apply)) }
            },
            dismissButton = { TextButton(onClick = { saveDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    renaming?.let { profile ->
        TextInputDialog(
            title = stringResource(R.string.profiles_rename),
            label = stringResource(R.string.profiles_name),
            initial = profile.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                if (name.isNotBlank()) scope.launch { profiles.rename(profile.id, name) }
            },
        )
    }

    applying?.let { profile ->
        AlertDialog(
            onDismissRequest = { applying = null },
            title = { Text(stringResource(R.string.profiles_apply_title, profile.name)) },
            text = { Text(stringResource(if (modified) R.string.profiles_apply_body_modified else R.string.profiles_apply_body)) },
            confirmButton = {
                TextButton(onClick = {
                    applying = null
                    scope.launch {
                        profiles.apply(profile.id)
                        Toast.makeText(context, context.getString(R.string.profiles_applied, profile.name), Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.profiles_apply)) }
            },
            dismissButton = { TextButton(onClick = { applying = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
