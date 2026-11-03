package ai.opencode.android.ui.config

import ai.opencode.android.R
import ai.opencode.android.data.model.McpConfig
import ai.opencode.android.data.model.McpServerInfo
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.EmptyState
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.StatusDot
import ai.opencode.android.ui.settings.MessageSnackbar
import ai.opencode.android.ui.settings.SettingsPage
import ai.opencode.android.ui.theme.StatusColors
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** A ready-made MCP server the user can add with one tap and then adjust. */
private data class McpTemplate(val name: String, val label: String, val config: McpConfig)

private val TEMPLATES = listOf(
    McpTemplate("context7", "Context7 (docs)", McpConfig.Remote("https://mcp.context7.com/mcp")),
    McpTemplate("deepwiki", "DeepWiki", McpConfig.Remote("https://mcp.deepwiki.com/mcp")),
    McpTemplate(
        "github",
        "GitHub",
        McpConfig.Remote("https://api.githubcopilot.com/mcp/", headers = mapOf("Authorization" to "Bearer <GITHUB_TOKEN>"), oauth = false),
    ),
    McpTemplate("sentry", "Sentry", McpConfig.Remote("https://mcp.sentry.dev/mcp")),
    McpTemplate("filesystem", "Filesystem", McpConfig.Local(listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/root", "/sdcard"))),
    McpTemplate("memory", "Memory", McpConfig.Local(listOf("npx", "-y", "@modelcontextprotocol/server-memory"))),
    McpTemplate("thinking", "Sequential thinking", McpConfig.Local(listOf("npx", "-y", "@modelcontextprotocol/server-sequential-thinking"))),
    McpTemplate("fetch", "Fetch", McpConfig.Local(listOf("uvx", "mcp-server-fetch"))),
    McpTemplate("playwright", "Playwright", McpConfig.Local(listOf("npx", "-y", "@playwright/mcp@latest", "--headless"))),
)

@Composable
fun McpScreen(container: AppContainer, onBack: () -> Unit, onOpenPhoneAccess: () -> Unit, onOpenEnv: () -> Unit = {}) {
    val viewModel: McpViewModel = viewModel(factory = McpViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var codeFor by remember { mutableStateOf<String?>(null) }
    var detailsFor by remember { mutableStateOf<String?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { viewModel.autoRefresh() }
    }

    MessageSnackbar(state.message, snackbar, viewModel::messageShown)
    LaunchedEffect(state.pendingAuthUrl) {
        val url = state.pendingAuthUrl ?: return@LaunchedEffect
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        viewModel.authUrlOpened()
    }

    Box {
        SettingsPage(title = stringResource(R.string.mcp_title), onBack = onBack, snackbarHostState = snackbar) {
            Text(
                text = stringResource(R.string.mcp_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            when {
                !state.connected -> EmptyState(title = stringResource(R.string.mcp_offline), message = stringResource(R.string.mcp_offline_body))
                state.loading && state.servers.isEmpty() -> LoadingIndicator()
                state.error != null && state.servers.isEmpty() -> EmptyState(title = stringResource(R.string.mcp_unavailable), message = state.error.orEmpty())
                else -> {
                    if (state.servers.isEmpty()) {
                        EmptyState(title = stringResource(R.string.mcp_empty), message = stringResource(R.string.mcp_empty_body))
                    } else {
                        StatusSummary(state.servers, onReconnectFailed = viewModel::reconnectFailed, onRefresh = { viewModel.refresh() })
                        SettingsGroup(title = stringResource(R.string.mcp_servers)) {
                            state.servers.forEachIndexed { index, server ->
                                if (index > 0) SettingsDivider()
                                McpRow(
                                    server = server,
                                    health = state.health[server.name],
                                    working = server.name in state.working,
                                    onToggle = { viewModel.toggle(server, it) },
                                    onOpen = { detailsFor = server.name },
                                    onEdit = { editing = EditTarget(server.name, server.config, existing = true) },
                                    onReconnect = { viewModel.reconnect(server.name) },
                                    onAuth = { viewModel.authenticate(server.name) },
                                    onCode = { codeFor = server.name },
                                    onLogout = { viewModel.logout(server.name) },
                                    onRemove = { viewModel.remove(server.name) },
                                    onPhoneAccess = onOpenPhoneAccess,
                                )
                            }
                        }
                    }
                    SettingsGroup(title = stringResource(R.string.mcp_templates)) {
                        TemplateChips { template -> editing = EditTarget(template.name, template.config, existing = false) }
                        SettingsItem(
                            title = stringResource(R.string.env_title),
                            subtitle = stringResource(R.string.mcp_env_hint),
                            icon = Icons.Default.Download,
                            showChevron = true,
                            onClick = onOpenEnv,
                        )
                    }
                }
            }
            Spacer(Modifier.padding(bottom = 72.dp))
        }
        if (state.connected) {
            ExtendedFloatingActionButton(
                onClick = { editing = EditTarget("", null, existing = false) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.mcp_add)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            )
        }
    }

    editing?.let { target ->
        McpEditor(
            target = target,
            onSave = { name, config ->
                viewModel.save(name, config, previousName = target.name.takeIf { target.existing })
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    detailsFor?.let { name ->
        val server = state.servers.firstOrNull { it.name == name }
        if (server == null) {
            detailsFor = null
        } else {
            McpDetails(
                server = server,
                health = state.health[name],
                working = name in state.working,
                onReconnect = { viewModel.reconnect(name) },
                onEdit = {
                    detailsFor = null
                    if (name == ANDROID_MCP) onOpenPhoneAccess() else editing = EditTarget(name, server.config, existing = true)
                },
                onDismiss = { detailsFor = null },
            )
        }
    }
    codeFor?.let { name ->
        CodeDialog(
            server = name,
            onSubmit = { code ->
                viewModel.submitCode(name, code)
                codeFor = null
            },
            onDismiss = { codeFor = null },
        )
    }
}

private data class EditTarget(val name: String, val config: McpConfig?, val existing: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateChips(onPick: (McpTemplate) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TEMPLATES.forEach { template ->
            AssistChip(onClick = { onPick(template) }, label = { Text(template.label) })
        }
    }
    Text(
        text = stringResource(R.string.mcp_templates_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
}

@Composable
private fun statusText(status: McpServerInfo.Status): Pair<String, Color> = when (status) {
    McpServerInfo.Status.CONNECTED -> stringResource(R.string.mcp_status_connected) to StatusColors.ok
    McpServerInfo.Status.PENDING -> stringResource(R.string.mcp_status_pending) to StatusColors.warn
    McpServerInfo.Status.DISABLED -> stringResource(R.string.mcp_status_disabled) to MaterialTheme.colorScheme.outline
    McpServerInfo.Status.FAILED -> stringResource(R.string.mcp_status_failed) to MaterialTheme.colorScheme.error
    McpServerInfo.Status.NEEDS_AUTH -> stringResource(R.string.mcp_status_auth) to StatusColors.warn
    McpServerInfo.Status.NEEDS_REGISTRATION -> stringResource(R.string.mcp_status_registration) to MaterialTheme.colorScheme.error
    McpServerInfo.Status.UNKNOWN -> stringResource(R.string.mcp_status_unknown) to MaterialTheme.colorScheme.outline
}

/** Counts by status, with one-tap reconnect of the failed ones. */
@Composable
private fun StatusSummary(servers: List<McpServerInfo>, onReconnectFailed: () -> Unit, onRefresh: () -> Unit) {
    val connected = servers.count { it.status == McpServerInfo.Status.CONNECTED }
    val failed = servers.count { it.status == McpServerInfo.Status.FAILED }
    val pending = servers.count { it.status == McpServerInfo.Status.PENDING }
    val auth = servers.count { it.status == McpServerInfo.Status.NEEDS_AUTH || it.status == McpServerInfo.Status.NEEDS_REGISTRATION }
    val off = servers.count { it.status == McpServerInfo.Status.DISABLED }
    SettingsGroup(title = stringResource(R.string.mcp_status_title)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SummaryLine(StatusColors.ok, stringResource(R.string.mcp_summary_connected, connected))
                if (pending > 0) SummaryLine(StatusColors.warn, stringResource(R.string.mcp_summary_pending, pending))
                if (auth > 0) SummaryLine(StatusColors.warn, stringResource(R.string.mcp_summary_auth, auth))
                if (failed > 0) SummaryLine(MaterialTheme.colorScheme.error, stringResource(R.string.mcp_summary_failed, failed))
                if (off > 0) SummaryLine(MaterialTheme.colorScheme.outline, stringResource(R.string.mcp_summary_disabled, off))
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_reload))
            }
        }
        if (failed > 0) {
            TextButton(onClick = onReconnectFailed, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.mcp_reconnect_failed, failed))
            }
        }
    }
}

@Composable
private fun SummaryLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "31 s", "2 min 5 s", "800 ms". */
@Composable
internal fun formatMillis(millis: Long): String = when {
    millis < 1_000 -> stringResource(R.string.duration_ms, millis)
    millis < 60_000 -> stringResource(R.string.duration_s, (millis + 500) / 1_000)
    else -> stringResource(R.string.duration_min_s, millis / 60_000, (millis % 60_000) / 1_000)
}

@Composable
private fun relativeTime(at: Long): String =
    android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), android.text.format.DateUtils.SECOND_IN_MILLIS).toString()

@Composable
private fun McpDetails(
    server: McpServerInfo,
    health: ai.opencode.android.data.mcp.McpMonitor.Health?,
    working: Boolean,
    onReconnect: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val (label, color) = statusText(server.status)
    val timeFormat = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp),
        title = { Text(server.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        health?.since?.let { stringResource(R.string.mcp_status_since, label, relativeTime(it)) } ?: label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (working) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                server.config?.let {
                    Text(summary(it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val error = server.error?.takeIf { it.isNotBlank() } ?: health?.lastError
                if (error != null) {
                    Text(
                        if (server.error.isNullOrBlank()) {
                            stringResource(R.string.mcp_last_error_at, health?.lastErrorAt?.let { timeFormat.format(java.util.Date(it)) }.orEmpty())
                        } else {
                            stringResource(R.string.mcp_error)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(error, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(server.name, error))
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text(stringResource(R.string.mcp_copy_error)) }
                }
                val connects = health?.connects.orEmpty()
                if (connects.isNotEmpty()) {
                    Text(stringResource(R.string.mcp_connects), style = MaterialTheme.typography.labelLarge)
                    connects.asReversed().forEach { connect ->
                        Text(
                            "${timeFormat.format(java.util.Date(connect.at))} · ${formatMillis(connect.millis)} · " +
                                (if (connect.ok) stringResource(R.string.mcp_connect_ok) else connect.error.orEmpty().take(120)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connect.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                val changes = health?.changes.orEmpty()
                if (changes.size > 1) {
                    Text(stringResource(R.string.mcp_history), style = MaterialTheme.typography.labelLarge)
                    changes.asReversed().forEach { change ->
                        val (text, tint) = statusText(change.status)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(tint)
                            Spacer(Modifier.width(8.dp))
                            Text("${timeFormat.format(java.util.Date(change.at))} · $text", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (connects.isEmpty() && changes.size <= 1) {
                    Text(stringResource(R.string.mcp_history_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !working, onClick = onReconnect) { Text(stringResource(R.string.mcp_reconnect)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

@Composable
private fun McpRow(
    server: McpServerInfo,
    health: ai.opencode.android.data.mcp.McpMonitor.Health?,
    working: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onReconnect: () -> Unit,
    onAuth: () -> Unit,
    onCode: () -> Unit,
    onLogout: () -> Unit,
    onRemove: () -> Unit,
    onPhoneAccess: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (label, color) = statusText(server.status)
    val phone = server.name == ANDROID_MCP
    val enabled = server.status != McpServerInfo.Status.DISABLED && server.config?.enabled != false
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(server.name, style = MaterialTheme.typography.bodyLarge)
            val connectTime = health?.lastConnect?.takeIf { it.ok }?.let { stringResource(R.string.mcp_connected_in, formatMillis(it.millis)) }
            Text(
                text = listOfNotNull(label, connectTime, server.config?.let(::summary)).joinToString(" · "),

                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            server.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (server.status == McpServerInfo.Status.NEEDS_AUTH) {
                TextButton(onClick = onAuth, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.mcp_sign_in))
                }
            }
        }
        if (working) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menu = false; if (phone) onPhoneAccess() else onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.mcp_reconnect)) }, onClick = { menu = false; onReconnect() })
                if (server.config is McpConfig.Remote || server.status == McpServerInfo.Status.NEEDS_AUTH) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.mcp_sign_in)) }, onClick = { menu = false; onAuth() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.mcp_paste_code)) }, onClick = { menu = false; onCode() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.mcp_sign_out)) }, onClick = { menu = false; onLogout() })
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onRemove() },
                )
            }
        }
    }
}

private fun summary(config: McpConfig): String = when (config) {
    is McpConfig.Local -> config.command.joinToString(" ").take(80)
    is McpConfig.Remote -> config.url
}

/** Splits a command line on spaces, honouring single and double quotes. */
internal fun splitCommand(line: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var started = false
    for (c in line) {
        when {
            quote != null && c == quote -> quote = null
            quote == null && (c == '"' || c == '\'') -> {
                quote = c
                started = true
            }
            quote == null && c.isWhitespace() -> {
                if (started) parts += current.toString()
                current.setLength(0)
                started = false
            }
            else -> {
                current.append(c)
                started = true
            }
        }
    }
    if (started) parts += current.toString()
    return parts
}

internal fun joinCommand(parts: List<String>): String =
    parts.joinToString(" ") { if (it.isEmpty() || it.any(Char::isWhitespace)) "\"$it\"" else it }

/** `KEY=value` lines ↔ map; `Header: value` uses [separator] ':'. */
internal fun parsePairs(text: String, separator: Char): Map<String, String> =
    text.lines().mapNotNull { line ->
        val index = line.indexOf(separator)
        if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
    }.filter { it.first.isNotEmpty() }.toMap()

internal fun formatPairs(map: Map<String, String>, separator: String): String =
    map.entries.joinToString("\n") { (key, value) -> "$key$separator$value" }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun McpEditor(target: EditTarget, onSave: (String, McpConfig) -> Unit, onDismiss: () -> Unit) {
    val initial = target.config
    var name by remember { mutableStateOf(target.name) }
    var remote by remember { mutableStateOf(initial is McpConfig.Remote || initial == null) }
    var url by remember { mutableStateOf((initial as? McpConfig.Remote)?.url.orEmpty()) }
    var headers by remember { mutableStateOf(formatPairs((initial as? McpConfig.Remote)?.headers.orEmpty(), ": ")) }
    var oauth by remember { mutableStateOf((initial as? McpConfig.Remote)?.oauth ?: true) }
    var command by remember { mutableStateOf(joinCommand((initial as? McpConfig.Local)?.command.orEmpty())) }
    var environment by remember { mutableStateOf(formatPairs((initial as? McpConfig.Local)?.environment.orEmpty(), "=")) }
    var cwd by remember { mutableStateOf((initial as? McpConfig.Local)?.cwd.orEmpty()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var timeout by remember { mutableStateOf(initial?.timeoutMs?.toString().orEmpty()) }

    val nameValid = NAME_PATTERN.matches(name)
    val valid = nameValid && if (remote) url.startsWith("http://") || url.startsWith("https://") else splitCommand(command).isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp).imePadding(),
        title = { Text(stringResource(if (target.existing) R.string.mcp_edit else R.string.mcp_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text(stringResource(R.string.mcp_name)) },
                    singleLine = true,
                    isError = name.isNotEmpty() && !nameValid,
                    supportingText = { Text(stringResource(R.string.mcp_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = remote, onClick = { remote = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text(stringResource(R.string.mcp_remote))
                    }
                    SegmentedButton(selected = !remote, onClick = { remote = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text(stringResource(R.string.mcp_local))
                    }
                }
                if (remote) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim() },
                        label = { Text("URL") },
                        placeholder = { Text("https://example.com/mcp") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = headers,
                        onValueChange = { headers = it },
                        label = { Text(stringResource(R.string.mcp_headers)) },
                        placeholder = { Text("Authorization: Bearer ...") },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchLine(stringResource(R.string.mcp_oauth), stringResource(R.string.mcp_oauth_hint), oauth) { oauth = it }
                } else {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.mcp_command)) },
                        placeholder = { Text("npx -y @modelcontextprotocol/server-memory") },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        supportingText = { Text(stringResource(R.string.mcp_command_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = environment,
                        onValueChange = { environment = it },
                        label = { Text(stringResource(R.string.mcp_env)) },
                        placeholder = { Text("API_KEY=...") },
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = cwd,
                        onValueChange = { cwd = it },
                        label = { Text(stringResource(R.string.mcp_cwd)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = timeout,
                    onValueChange = { value -> timeout = value.filter(Char::isDigit).take(7) },
                    label = { Text(stringResource(R.string.mcp_timeout)) },
                    placeholder = { Text("30000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchLine(stringResource(R.string.mcp_enabled), null, enabled) { enabled = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val timeoutMs = timeout.toIntOrNull()?.takeIf { it > 0 }
                    val config = if (remote) {
                        McpConfig.Remote(url, parsePairs(headers, ':'), oauth, enabled, timeoutMs)
                    } else {
                        McpConfig.Local(splitCommand(command), parsePairs(environment, '='), cwd.ifBlank { null }, enabled, timeoutMs)
                    }
                    onSave(name, config)
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private val NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,40}$")

@Composable
internal fun SwitchLine(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CodeDialog(server: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mcp_paste_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.mcp_paste_code_hint, server), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = code.isNotBlank(), onClick = { onSubmit(code) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
