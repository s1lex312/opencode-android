package ai.opencode.android.ui.config

import ai.opencode.android.R
import ai.opencode.android.data.config.ConfigCheck
import ai.opencode.android.data.config.ConfigDocument
import ai.opencode.android.data.config.ConfigEditor
import ai.opencode.android.data.config.ConfigVersion
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.theme.csp
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings → opencode config: the raw `opencode.json` with live validation against opencode's
 * schema, formatting, and rollback to one of the last 20 saved versions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigEditorScreen(container: AppContainer, onBack: () -> Unit) {
    val editor = remember {
        ConfigEditor(container.context, container.envPaths, container.settingsRepository, container.sessionRepository, ai.opencode.android.server.provision.EnvConfig.downloadClient())
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<ConfigDocument?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var check by remember { mutableStateOf<ConfigCheck?>(null) }
    var saving by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { editor.load() }
            .onSuccess {
                document = it
                text = it.text
            }
            .onFailure { loadError = it.message }
    }
    // Validate a moment after typing stops.
    LaunchedEffect(text) {
        if (document == null) return@LaunchedEffect
        delay(600)
        check = editor.check(text)
    }

    fun save() {
        val current = document ?: return
        saving = true
        scope.launch {
            runCatching { editor.save(current, text) }
                .onSuccess { restart ->
                    document = current.copy(text = text)
                    if (restart) container.restartLocalServerService()
                    Toast.makeText(context, if (restart) R.string.config_saved_restart else R.string.config_saved_remote, Toast.LENGTH_SHORT).show()
                }
                .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            saving = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.config_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = { scope.launch { check = editor.check(text) } }) {
                        Icon(Icons.Default.FactCheck, contentDescription = stringResource(R.string.config_validate))
                    }
                    IconButton(onClick = {
                        editor.format(text)?.let { text = it } ?: Toast.makeText(context, R.string.config_format_failed, Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.FormatAlignLeft, contentDescription = stringResource(R.string.config_format)) }
                    if (document?.path != null) {
                        IconButton(onClick = { historyOpen = true }) { Icon(Icons.Default.History, contentDescription = stringResource(R.string.config_history)) }
                    }
                    IconButton(enabled = !saving && document != null && text != document?.text && check !is ConfigCheck.SyntaxError, onClick = {
                        val errors = (check as? ConfigCheck.Checked)?.errors.orEmpty()
                        if (errors.isNotEmpty()) confirmSave = true else save()
                    }) {
                        if (saving) CircularProgressIndicator(strokeWidth = 2.dp) else Icon(Icons.Default.Save, contentDescription = stringResource(R.string.config_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val current = document
            if (current == null) {
                Text(
                    loadError?.let { stringResource(R.string.config_load_failed, it) } ?: stringResource(R.string.config_loading),
                    modifier = Modifier.padding(16.dp),
                    color = if (loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                text = current.path ?: stringResource(R.string.config_remote_hint),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            CheckBanner(check)
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.csp, lineHeight = 19.csp, color = MaterialTheme.colorScheme.onSurface)
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text(stringResource(R.string.config_save_with_errors)) },
            text = { Text(stringResource(R.string.config_save_with_errors_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    save()
                }) { Text(stringResource(R.string.config_save)) }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (historyOpen) {
        val versions = remember { editor.history() }
        HistoryDialog(
            versions = versions,
            onDismiss = { historyOpen = false },
            onRestore = { version ->
                historyOpen = false
                text = editor.read(version)
                Toast.makeText(context, R.string.config_history_loaded, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun CheckBanner(check: ConfigCheck?) {
    val (color, lines) = when (check) {
        null -> return
        is ConfigCheck.SyntaxError -> MaterialTheme.colorScheme.error to listOf(stringResource(R.string.config_syntax_error, check.message))
        is ConfigCheck.Checked -> when {
            check.errors.isNotEmpty() -> MaterialTheme.colorScheme.error to
                listOf(stringResource(R.string.config_schema_errors, check.errors.size)) + check.errors.map { "${it.path}: ${it.message}" }
            check.schemaUsed -> ai.opencode.android.ui.theme.StatusColors.ok to listOf(stringResource(R.string.config_valid))
            else -> MaterialTheme.colorScheme.tertiary to listOf(stringResource(R.string.config_valid_no_schema))
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .background(color.copy(alpha = 0.12f))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEachIndexed { index, line ->
            Text(
                line,
                style = if (index == 0) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                fontFamily = if (index == 0) null else FontFamily.Monospace,
                color = color,
            )
        }
    }
}

@Composable
private fun HistoryDialog(versions: List<ConfigVersion>, onDismiss: () -> Unit, onRestore: (ConfigVersion) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.config_history)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (versions.isEmpty()) Text(stringResource(R.string.config_history_empty))
                versions.forEach { version ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(version.createdAt))} · ${version.sizeBytes} B",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        )
                        TextButton(onClick = { onRestore(version) }) { Text(stringResource(R.string.config_history_open)) }
                    }
                }
                Text(stringResource(R.string.config_history_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
