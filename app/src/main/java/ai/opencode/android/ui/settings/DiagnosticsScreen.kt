package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.data.diagnostics.DiagItem
import ai.opencode.android.data.diagnostics.DiagSection
import ai.opencode.android.data.diagnostics.DiagStatus
import ai.opencode.android.data.diagnostics.Diagnostics
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.StatusDot
import ai.opencode.android.ui.theme.StatusColors
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings → Diagnostics: health of every moving part, a copyable report and a bug-report zip. */
@Composable
fun DiagnosticsScreen(container: AppContainer, onBack: () -> Unit, onOpenLogs: () -> Unit) {
    val diagnostics = remember { Diagnostics(container) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<DiagItem>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { items = diagnostics.collect() }

    val bundleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val current = items
        if (uri != null && current != null) {
            scope.launch {
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { diagnostics.writeBundle(it, current) } ?: error("no stream")
                }.isSuccess
                Toast.makeText(context, if (ok) R.string.diag_bundle_saved else R.string.diag_bundle_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    SettingsPage(
        title = stringResource(R.string.diag_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.diag_refresh)) }
        },
    ) {
        val current = items
        if (current == null) {
            CircularProgressIndicator(Modifier.padding(24.dp))
            return@SettingsPage
        }
        val problems = current.count { it.status == DiagStatus.ERROR || it.status == DiagStatus.WARN }
        Text(
            text = if (problems == 0) stringResource(R.string.diag_all_ok) else stringResource(R.string.diag_problems, problems),
            style = MaterialTheme.typography.titleMedium,
            color = if (problems == 0) StatusColors.ok else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        DiagSection.entries.forEach { section ->
            val rows = current.filter { it.section == section }
            if (rows.isEmpty()) return@forEach
            SettingsGroup(title = stringResource(section.title)) {
                rows.forEachIndexed { index, item ->
                    if (index > 0) SettingsDivider()
                    DiagRow(item)
                }
            }
        }
        SettingsGroup(title = stringResource(R.string.diag_actions)) {
            SettingsItem(
                title = stringResource(R.string.diag_copy),
                icon = Icons.Default.ContentCopy,
                onClick = { copyText(context, "diagnostics", diagnostics.report(current)) },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.diag_bundle),
                subtitle = stringResource(R.string.diag_bundle_hint),
                icon = Icons.Default.Archive,
                onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    bundleLauncher.launch("opencode-android-diagnostics-$stamp.zip")
                },
            )
            SettingsDivider()
            SettingsItem(title = stringResource(R.string.diag_open_logs), icon = Icons.Default.Terminal, showChevron = true, onClick = onOpenLogs)
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.diag_restart_server),
                icon = Icons.Default.RestartAlt,
                onClick = {
                    container.restartLocalServerService()
                    Toast.makeText(context, R.string.diag_restarting, Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

@Composable
private fun DiagRow(item: DiagItem) {
    val color = when (item.status) {
        DiagStatus.OK -> StatusColors.ok
        DiagStatus.WARN -> StatusColors.warn
        DiagStatus.ERROR -> MaterialTheme.colorScheme.error
        DiagStatus.INFO -> MaterialTheme.colorScheme.outline
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        StatusDot(color, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(item.title), style = MaterialTheme.typography.bodyMedium)
            Text(item.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
