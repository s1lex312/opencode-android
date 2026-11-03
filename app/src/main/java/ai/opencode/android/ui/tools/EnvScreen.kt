package ai.opencode.android.ui.tools

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SwitchRow
import ai.opencode.android.ui.settings.SettingsPage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** One-tap node, python and git for local MCP servers (`npx`, `uvx`) and the git panel. */
@Composable
fun EnvScreen(container: AppContainer, onBack: () -> Unit, onOpenMcp: () -> Unit) {
    val viewModel: EnvViewModel = viewModel(factory = EnvViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var node by rememberSaveable { mutableStateOf(true) }
    var python by rememberSaveable { mutableStateOf(true) }
    var git by rememberSaveable { mutableStateOf(true) }

    SettingsPage(title = stringResource(R.string.env_title), onBack = onBack) {
        Text(
            stringResource(R.string.env_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }
        SettingsGroup(title = stringResource(R.string.env_installed)) {
            if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            EnvTool.entries.forEach { tool ->
                val version = state.versions[tool]
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (version != null) "✓" else "✗",
                        color = if (version != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(22.dp),
                    )
                    Text(tool.binary, fontFamily = FontFamily.Monospace, modifier = Modifier.width(80.dp))
                    Text(
                        version ?: stringResource(R.string.env_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            state.packageManager?.let {
                Text(
                    stringResource(R.string.env_package_manager, it) + if (!state.root) " · " + stringResource(R.string.env_not_root) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.env_install)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SwitchRow(stringResource(R.string.env_pkg_node), node, { node = it }, subtitle = stringResource(R.string.env_pkg_node_hint, EnvScripts.NODE_VERSION))
                SwitchRow(stringResource(R.string.env_pkg_python), python, { python = it }, subtitle = stringResource(R.string.env_pkg_python_hint))
                SwitchRow(stringResource(R.string.env_pkg_git), git, { git = it }, subtitle = stringResource(R.string.env_pkg_git_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val packages = buildSet {
                        if (node) add(EnvPackage.NODE)
                        if (python) add(EnvPackage.PYTHON)
                        if (git) add(EnvPackage.GIT)
                    }
                    Button(onClick = { viewModel.install(packages) }, enabled = packages.isNotEmpty() && !state.installing) {
                        if (state.installing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.installing) R.string.env_installing else R.string.env_install_button))
                    }
                    OutlinedButton(onClick = viewModel::check, enabled = !state.installing && !state.checking) {
                        Text(stringResource(R.string.env_recheck))
                    }
                }
                Text(stringResource(R.string.env_size_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (state.log.isNotEmpty() || state.installing) {
            val result = state.exitCode
            Text(
                when {
                    state.installing -> stringResource(R.string.env_log_running)
                    result == 0 -> stringResource(R.string.env_log_ok)
                    else -> stringResource(R.string.env_log_failed, result?.toString() ?: "?")
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (result != null && result != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 6.dp),
            )
            LogBox(state.log)
        }

        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.mcp_title),
                subtitle = stringResource(R.string.env_open_mcp),
                icon = Icons.Default.Hub,
                showChevron = true,
                onClick = onOpenMcp,
            )
        }
    }
}

@Composable
private fun LogBox(text: String) {
    val scroll = rememberScrollState()
    LaunchedEffect(text.length) { scroll.scrollTo(scroll.maxValue) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Box(Modifier.heightIn(max = 320.dp).verticalScroll(scroll).horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(text.ifEmpty { "…" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false)
        }
    }
}

