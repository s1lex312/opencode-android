package ai.opencode.android.ui.logs

import ai.opencode.android.R
import ai.opencode.android.data.diagnostics.Redact
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.settings.copyText
import ai.opencode.android.ui.theme.LocalUiTokens
import ai.opencode.android.ui.theme.StatusColors
import ai.opencode.android.ui.theme.csp
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogLevel { ALL, WARN, ERROR }

private fun levelOf(line: String): LogLevel = when {
    ERROR_WORDS.containsMatchIn(line) -> LogLevel.ERROR
    WARN_WORDS.containsMatchIn(line) -> LogLevel.WARN
    else -> LogLevel.ALL
}

private val ERROR_WORDS = Regex("(?i)\\b(error|fatal|exception|failed|panic| E/)\\b|^E ")
private val WARN_WORDS = Regex("(?i)\\b(warn|warning|retry|restarting|timeout| W/)\\b|^W ")

/** Server and app logs: search, level filter, wrapping, follow, copy and save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: LogsViewModel = viewModel(factory = LogsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf(LogLevel.ALL) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var appLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var appRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab, appRefresh) {
        if (tab == 1) appLines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.appLog() }
    }

    val source = if (tab == 0) state.lines else appLines
    val lines = remember(source, query, level) {
        source.filter { line ->
            (query.isBlank() || line.contains(query, ignoreCase = true)) &&
                when (level) {
                    LogLevel.ALL -> true
                    LogLevel.WARN -> levelOf(line) != LogLevel.ALL
                    LogLevel.ERROR -> levelOf(line) == LogLevel.ERROR
                }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, follow) {
        if (follow && lines.isNotEmpty()) runCatching { listState.scrollToItem(lines.lastIndex) }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val text = Redact.text(lines.joinToString("\n"))
            val ok = runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("no stream") }.isSuccess
            Toast.makeText(context, if (ok) R.string.logs_saved else R.string.logs_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { searching = !searching }) { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.logs_search)) }
                    IconButton(onClick = { if (tab == 0) viewModel.refresh() else appRefresh++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.logs_reload))
                    }
                    IconButton(onClick = { copyText(context, "log", Redact.text(lines.joinToString("\n"))) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.logs_copy))
                    }
                    IconButton(onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        saveLauncher.launch(if (tab == 0) "opencode-server-$stamp.log" else "opencode-app-$stamp.log")
                    }) { Icon(Icons.Default.SaveAlt, contentDescription = stringResource(R.string.logs_save)) }
                    if (tab == 0) {
                        IconButton(onClick = viewModel::clear) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.logs_clear)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.logs_tab_server)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.logs_tab_app)) })
            }
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.logs_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LogLevel.entries.forEach { option ->
                    FilterChip(
                        selected = level == option,
                        onClick = { level = option },
                        label = {
                            Text(
                                stringResource(
                                    when (option) {
                                        LogLevel.ALL -> R.string.logs_level_all
                                        LogLevel.WARN -> R.string.logs_level_warn
                                        LogLevel.ERROR -> R.string.logs_level_error
                                    },
                                ),
                            )
                        },
                    )
                }
                IconToggleButton(checked = wrap, onCheckedChange = { wrap = it }) {
                    Icon(Icons.AutoMirrored.Filled.WrapText, contentDescription = stringResource(R.string.logs_wrap))
                }
                IconToggleButton(checked = follow, onCheckedChange = { follow = it }) {
                    Icon(Icons.Default.VerticalAlignBottom, contentDescription = stringResource(R.string.logs_follow))
                }
            }
            Text(
                text = if (tab == 0) {
                    stringResource(if (state.fromFile) R.string.logs_from_disk else R.string.logs_live, state.logPath) +
                        " · " + stringResource(R.string.logs_count, lines.size, source.size)
                } else {
                    stringResource(R.string.logs_count, lines.size, source.size)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                val warn = StatusColors.warn
                val error = MaterialTheme.colorScheme.error
                val normal = MaterialTheme.colorScheme.onSurface
                val horizontal = rememberScrollState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().let { if (wrap) it else it.horizontalScroll(horizontal) },
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(LocalUiTokens.current.gap(2f)),
                ) {
                    items(lines) { line ->
                        val color: Color = when (levelOf(line)) {
                            LogLevel.ERROR -> error
                            LogLevel.WARN -> warn
                            LogLevel.ALL -> normal
                        }
                        Text(
                            text = line,
                            fontSize = 11.csp,
                            lineHeight = 15.csp,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                            softWrap = wrap,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}
