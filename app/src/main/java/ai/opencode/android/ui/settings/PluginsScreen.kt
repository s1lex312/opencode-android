package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.plugins.CatalogEntry
import ai.opencode.android.plugins.InstalledPlugin
import ai.opencode.android.plugins.PluginPreview
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Settings → Plugins: installed plugins, the catalogue, install from a link or a file. */
@Composable
fun PluginsScreen(container: AppContainer, onBack: () -> Unit, onOpenEnv: () -> Unit) {
    val manager = container.plugins
    val state by manager.state.collectAsState()
    val settings by container.settingsRepository.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var preview by remember { mutableStateOf<PluginPreview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var linkDialog by rememberSaveable { mutableStateOf(false) }
    var catalogDialog by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<InstalledPlugin?>(null) }

    // Installs and removals outlive this screen: connecting an npx MCP server can take a minute,
    // and leaving the screen must not cancel the work half-way (config patched, plugin unrecorded).
    val work = container.appScope
    val app = context.applicationContext

    fun fail(t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) return
        Toast.makeText(app, t.message ?: t.javaClass.simpleName, Toast.LENGTH_LONG).show()
    }

    fun open(url: String, sha: String? = null) {
        loading = true
        scope.launch {
            runCatching { manager.preview(url, sha) }.onSuccess { preview = it }.onFailure(::fail)
            loading = false
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("no stream")
                manager.previewText(text, uri.lastPathSegment ?: "file")
            }.onSuccess { preview = it }.onFailure(::fail)
        }
    }

    LaunchedEffect(Unit) {
        if (state.catalog.isEmpty()) manager.loadCatalog()
        // Plugins are a handful of small JSON files: checking them on every visit is cheap.
        if (state.installed.isNotEmpty()) work.launch { runCatching { manager.checkUpdates() } }
    }

    SettingsPage(
        title = stringResource(R.string.plugins_title),
        onBack = onBack,
        actions = {
            if (loading) CircularProgressIndicator(Modifier.padding(12.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        },
    ) {
        Text(
            text = stringResource(R.string.plugins_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SettingsGroup(title = stringResource(R.string.plugins_installed)) {
            if (state.installed.isEmpty() && state.installing.isEmpty()) {
                Text(
                    stringResource(R.string.plugins_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            state.installed.forEachIndexed { index, plugin ->
                if (index > 0) SettingsDivider()
                InstalledRow(
                    plugin = plugin,
                    busy = plugin.manifest.id in state.busy,
                    downloading = state.downloading[plugin.manifest.id],
                    update = state.updates[plugin.manifest.id],
                    onToggle = { enabled -> work.launch { runCatching { manager.setEnabled(plugin.manifest.id, enabled) }.onFailure(::fail) } },
                    onUpdate = { preview = state.updates[plugin.manifest.id] },
                    onRemove = { removing = plugin },
                )
            }
            state.installing.filterKeys { id -> state.installed.none { it.manifest.id == id } }.forEach { (id, name) ->
                if (state.installed.isNotEmpty()) SettingsDivider()
                InstallingRow(name, downloading = state.downloading[id])
            }
            if (state.installed.isNotEmpty()) {
                SettingsDivider()
                SettingsItem(
                    title = stringResource(R.string.plugins_check_updates),
                    subtitle = when {
                        state.checkingUpdates -> stringResource(R.string.plugins_checking_updates)
                        state.updates.isEmpty() -> stringResource(R.string.plugins_no_updates)
                        else -> stringResource(R.string.plugins_updates_found, state.updates.size)
                    },
                    icon = Icons.Default.Refresh,
                    onClick = { if (!state.checkingUpdates) work.launch { runCatching { manager.checkUpdates() }.onFailure(::fail) } },
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.plugins_add)) {
            SettingsItem(
                title = stringResource(R.string.plugins_from_link),
                subtitle = stringResource(R.string.plugins_from_link_hint),
                icon = Icons.Default.Link,
                onClick = { linkDialog = true },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.plugins_from_file),
                icon = Icons.Default.FileOpen,
                onClick = { fileLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
        }

        SettingsGroup(title = stringResource(R.string.plugins_catalog)) {
            SettingsItem(
                title = stringResource(R.string.plugins_catalog_source),
                subtitle = settings?.pluginCatalogUrl,
                icon = Icons.Default.Storefront,
                onClick = { catalogDialog = true },
                trailing = {
                    IconButton(onClick = { scope.launch { manager.loadCatalog() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.plugins_catalog_reload))
                    }
                },
            )
            state.catalogError?.let {
                Text(
                    stringResource(R.string.plugins_catalog_error, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (state.catalogLoading) {
                CircularProgressIndicator(Modifier.padding(16.dp), strokeWidth = 2.dp)
            }
            state.catalog.forEach { entry ->
                SettingsDivider()
                CatalogRow(
                    entry,
                    installed = state.installed.any { it.manifest.id == entry.id },
                    installing = entry.id in state.installing,
                ) { open(entry.url, entry.sha256) }
            }
        }
    }

    preview?.let { current ->
        val updating = state.installed.firstOrNull { it.manifest.id == current.manifest.id }
        PreviewDialog(
            preview = current,
            updateFrom = updating,
            busy = current.manifest.id in state.busy,
            onDismiss = { preview = null },
            onOpenEnv = onOpenEnv,
            onInstall = { enableHooks ->
                preview = null
                work.launch {
                    runCatching { manager.install(current, enableHooks) }
                        .onSuccess { installed ->
                            val text = if (installed.warnings.isEmpty()) {
                                app.getString(R.string.plugins_installed_toast, installed.manifest.name)
                            } else {
                                app.getString(R.string.plugins_installed_partly, installed.manifest.name, installed.warnings.joinToString("; "))
                            }
                            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                        }
                        .onFailure(::fail)
                }
            },
        )
    }

    if (linkDialog) {
        var url by rememberSaveable { mutableStateOf("") }
        var sha by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text(stringResource(R.string.plugins_from_link)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = sha, onValueChange = { sha = it }, label = { Text(stringResource(R.string.plugins_sha_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("https://"), onClick = {
                    linkDialog = false
                    open(url, sha)
                }) { Text(stringResource(R.string.plugins_check)) }
            },
            dismissButton = { TextButton(onClick = { linkDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (catalogDialog) {
        TextInputDialog(
            title = stringResource(R.string.plugins_catalog_source),
            label = "https://…/index.json",
            initial = settings?.pluginCatalogUrl.orEmpty(),
            onDismiss = { catalogDialog = false },
            onConfirm = { value ->
                catalogDialog = false
                scope.launch {
                    container.settingsRepository.update {
                        it.copy(pluginCatalogUrl = value.trim().ifBlank { ai.opencode.android.settings.AppSettings.DEFAULT_PLUGIN_CATALOG })
                    }
                    manager.loadCatalog()
                }
            },
        )
    }

    removing?.let { plugin ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.plugins_remove_title, plugin.manifest.name)) },
            text = { Text(stringResource(R.string.plugins_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    work.launch {
                        runCatching { manager.uninstall(plugin.manifest.id) }
                            .onSuccess { warnings ->
                                val text = if (warnings.isEmpty()) {
                                    app.getString(R.string.plugins_removed, plugin.manifest.name)
                                } else {
                                    app.getString(R.string.plugins_removed_partly, plugin.manifest.name, warnings.joinToString("; "))
                                }
                                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                            }
                            .onFailure(::fail)
                    }
                }) { Text(stringResource(R.string.plugins_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun InstalledRow(
    plugin: InstalledPlugin,
    busy: Boolean,
    downloading: String?,
    update: PluginPreview?,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("${plugin.manifest.name} ${plugin.manifest.pluginVersion}", style = MaterialTheme.typography.bodyLarge)
            if (plugin.manifest.description.isNotBlank()) {
                Text(plugin.manifest.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            plugin.warnings.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            if (busy && downloading != null) {
                Text(stringResource(R.string.plugins_downloading, downloading), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (update != null && !busy) {
                TextButton(onClick = onUpdate, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.plugins_update_to, versionChange(plugin, update)))
                }
            }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
        } else {
            Switch(checked = plugin.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.plugins_remove)) }
        }
    }
}

/** "1.0.0 → 1.1.0", or "1.0.0 (changed)" when the content changed under the same version. */
@Composable
private fun versionChange(plugin: InstalledPlugin, update: PluginPreview): String {
    val from = plugin.manifest.pluginVersion
    val to = update.manifest.pluginVersion
    return when {
        from != to -> "$from → $to"
        update.sha256 == plugin.sha256 -> to
        else -> stringResource(R.string.plugins_same_version_changed, to)
    }
}

@Composable
private fun InstallingRow(name: String, downloading: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (downloading != null) stringResource(R.string.plugins_downloading, downloading) else stringResource(R.string.plugins_installing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CircularProgressIndicator(Modifier.padding(8.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun CatalogRow(entry: CatalogEntry, installed: Boolean, installing: Boolean, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = !installing, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            when {
                installing -> Text(stringResource(R.string.plugins_badge_installing), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                installed -> Text(stringResource(R.string.plugins_badge_installed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (entry.description.isNotBlank()) {
            Text(entry.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.tags.isNotEmpty()) {
            Text(entry.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun PreviewDialog(
    preview: PluginPreview,
    updateFrom: InstalledPlugin?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onOpenEnv: () -> Unit,
    onInstall: (Boolean) -> Unit,
) {
    val manifest = preview.manifest
    val p = preview.permissions
    // An update keeps the user's choice about hooks.
    var enableHooks by rememberSaveable { mutableStateOf(updateFrom?.hooksEnabled ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (updateFrom != null) {
                    stringResource(R.string.plugins_update_title, manifest.name, versionChange(updateFrom, preview))
                } else {
                    "${manifest.name} ${manifest.pluginVersion}"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (manifest.description.isNotBlank()) Text(manifest.description, style = MaterialTheme.typography.bodyMedium)
                if (manifest.author.isNotBlank()) Text(stringResource(R.string.plugins_author, manifest.author), style = MaterialTheme.typography.bodySmall)
                Text(preview.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("SHA-256 ${preview.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.plugins_will), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                if (p.programs.isNotEmpty()) PermissionBlock(stringResource(R.string.plugins_perm_programs), p.programs, warn = true)
                if (p.hosts.isNotEmpty()) PermissionBlock(stringResource(R.string.plugins_perm_hosts), p.hosts)
                if (p.agents.isNotEmpty()) PermissionBlock(stringResource(R.string.plugins_perm_agents), p.agents)
                if (p.commands.isNotEmpty()) PermissionBlock(stringResource(R.string.plugins_perm_commands), p.commands.map { "/$it" })
                if (p.prompts > 0) Text(stringResource(R.string.plugins_perm_prompts, p.prompts), style = MaterialTheme.typography.bodySmall)
                if (p.snippets.isNotEmpty()) PermissionBlock(stringResource(R.string.plugins_perm_snippets), p.snippets.map { "/$it" })
                if (p.hooks.isNotEmpty()) {
                    PermissionBlock(stringResource(R.string.plugins_perm_hooks), p.hooks, warn = true)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { enableHooks = !enableHooks }) {
                        Checkbox(checked = enableHooks, onCheckedChange = { enableHooks = it })
                        Text(stringResource(R.string.plugins_enable_hooks), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (manifest.requires.isNotEmpty()) {
                    Text(
                        stringResource(R.string.plugins_requires, manifest.requires.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.clickable(onClick = onOpenEnv),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { onInstall(enableHooks) }) {
                Text(
                    stringResource(
                        when {
                            busy -> R.string.plugins_badge_installing
                            updateFrom != null -> R.string.plugins_update
                            else -> R.string.plugins_install
                        },
                    ),
                )

            }
        },

        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PermissionBlock(title: String, items: List<String>, warn: Boolean = false) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}
