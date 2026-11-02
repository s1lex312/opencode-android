package ai.opencode.android.ui.setup

import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.server.provision.ProvisionState
import ai.opencode.android.settings.ConnectionMode
import ai.opencode.android.ui.components.AdaptiveContent
import ai.opencode.android.ui.components.ErrorCard
import ai.opencode.android.ui.components.SectionCard
import ai.opencode.android.ui.components.SwitchRow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * First-run / maintenance screen for "everything on the phone" mode.
 *
 * It installs the environment (proot + rootfs + opencode), starts the server, shows progress and
 * diagnostics, and lets the user clean everything up again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val viewModel: SetupViewModel = viewModel(factory = SetupViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setNotificationsGranted(granted) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDiskUsage(); viewModel.refreshBatteryState() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AdaptiveContent(modifier = Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item { ModeSection(state.mode, viewModel::setMode) }
                item { EnvironmentSection(state, viewModel) }
                item { SourceSection(state, viewModel) }
                item { ServerSection(state, viewModel, onOpenLogs) }
                item {
                    PermissionsSection(
                        state = state,
                        onRequestNotifications = {
                            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onOpenBatterySettings = {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                ).apply { data = android.net.Uri.parse("package:" + context.packageName) }
                                context.startActivity(intent)
                            }
                        },
                    )
                }
                item { LogsPreview(state.logs, onOpenLogs, viewModel::clearLogs) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ModeSection(mode: ConnectionMode, onMode: (ConnectionMode) -> Unit) {
    SectionCard(
        title = "How should opencode run?",
        description = "Local mode installs opencode on this phone and needs ~400 MB of free space. " +
            "Remote mode connects to an opencode server on your computer or a VPS.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == ConnectionMode.LOCAL,
                onClick = { onMode(ConnectionMode.LOCAL) },
                label = { Text("On this phone") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = mode == ConnectionMode.REMOTE,
                onClick = { onMode(ConnectionMode.REMOTE) },
                label = { Text("Remote server") },
            )
        }
        Text(
            text = if (mode == ConnectionMode.LOCAL) {
                "The app runs proot + rootfs + opencode in its own sandbox, then connects to http://127.0.0.1."
            } else {
                "Configure the address and credentials in Settings → Server connection."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EnvironmentSection(state: SetupUiState, viewModel: SetupViewModel) {
    SectionCard(
        title = "Environment",
        description = "proot + minimal root filesystem + the opencode server binary, installed into " +
            "the app's private storage. No root, no Termux, nothing outside the sandbox.",
    ) {
        InfoRow("Build", state.buildLabel.ifBlank { state.flavor })
        InfoRow("Installed", state.installedVersion ?: "not installed")
        InfoRow("Bundle", "${state.effectiveManifest.version} · ${state.bundleSizeLabel}")
        InfoRow(
            "Bundle source",
            when {
                state.embeddedBundle -> state.embeddedBundleLabel
                state.effectiveManifest.isDownload -> "downloaded from ${state.effectiveManifest.url}"
                else -> state.embeddedBundleLabel.ifBlank { "not embedded" }
            },
        )

        if (state.brokenFullBuild) {
            Text(
                text = "This is a Full build, but its environment bundle is missing from the APK. " +
                    "The artifact is broken — please install the Full APK from the latest release " +
                    "again, or use a bundle URL / imported file below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        InfoRow("On disk", state.sizeLabel)
        InfoRow("Free space", state.freeSpaceLabel)
        Text(
            text = state.envPath,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.provision.busy) {
            val phase = when (state.provision.phase) {
                ProvisionState.Phase.DOWNLOADING ->
                    if (state.effectiveManifest.isDownload) "Downloading" else "Embedded environment — unpacking"
                ProvisionState.Phase.VERIFYING -> "Verifying SHA-256"
                ProvisionState.Phase.EXTRACTING -> "Unpacking"
                ProvisionState.Phase.INSTALLING -> "Installing"
                else -> "Preparing"
            }
            Text(
                text = if (state.isFullBuild && !state.effectiveManifest.isDownload) {
                    "Environment is built into this APK. Unpacking… $phase (${state.progressPercent}%)"
                } else {
                    "$phase… ${state.progressPercent}%"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            LinearProgressIndicator(
                progress = { state.provision.progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!state.embeddedBundle && !state.brokenFullBuild && !state.effectiveManifest.isDownload) {
            Text(
                text = "Environment not found in this APK. Enter a bundle URL below or import a .tar.gz " +
                    "file — or switch to Remote mode and connect to your own server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.provision.error?.let { error ->
            ErrorCard(message = error, onRetry = { viewModel.install() })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.install() }, enabled = !state.provision.busy) {
                if (state.provision.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.installed) "Reinstall" else "Install")
            }
            OutlinedButton(
                onClick = { viewModel.clearEnvironment() },
                enabled = state.installed || state.provision.phase == ProvisionState.Phase.FAILED,
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Clear")
            }
        }
    }
}

@Composable
private fun SourceSection(state: SetupUiState, viewModel: SetupViewModel) {
    val pickBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.installFromFile(it) }
    }

    SectionCard(
        title = "Artifact source",
        description = if (state.embeddedBundle) {
            "This build already contains the environment — nothing to download. You can still point " +
                "it at a newer bundle published as https:// URL + SHA-256."
        } else if (state.brokenFullBuild) {
            "This Full build lost its bundle. Install a bundle file or paste an https:// URL + SHA-256 " +
                "to recover without reinstalling."
        } else {
            "No environment in this APK (Lite build). Paste the https:// URL and the SHA-256 printed " +
                "by build-rootfs.sh, or import a bundle file."
        },
    ) {
        OutlinedTextField(
            value = state.draftUrl,
            onValueChange = viewModel::onUrlChange,
            label = { Text("Bundle URL (https://…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.draftSha,
            onValueChange = viewModel::onShaChange,
            label = { Text("SHA-256") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.saveArtifactSource() }) { Text("Save source") }
            OutlinedButton(
                onClick = {
                    pickBundle.launch(
                        arrayOf("application/gzip", "application/x-gzip", "application/octet-stream"),
                    )
                },
                enabled = !state.provision.busy,
            ) { Text("Import file…") }
            Text(
                text = if (state.effectiveManifest.isDownload) "Download mode" else "APK asset mode",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServerSection(state: SetupUiState, viewModel: SetupViewModel, onOpenLogs: () -> Unit) {
    val server = state.server
    SectionCard(
        title = "Server",
        description = "opencode serve runs inside proot and listens on 127.0.0.1:${server.port}.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = phaseLabel(server),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (server.pid != null) {
                Text("pid ${server.pid}", style = MaterialTheme.typography.labelSmall)
            }
        }
        server.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (server.restarts > 0) {
            Text(
                "Restarts: ${server.restarts}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.startServer() }, enabled = !server.running && state.installed) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start")
            }
            OutlinedButton(onClick = { viewModel.stopServer() }, enabled = server.running) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stop")
            }
            AssistChip(onClick = { viewModel.restartServer() }, label = { Text("Restart") })
            AssistChip(
                onClick = onOpenLogs,
                label = { Text("Logs") },
                leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        if (!state.installed) {
            Text(
                "Install the environment first — the server needs proot, a rootfs and the opencode binary.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PermissionsSection(
    state: SetupUiState,
    onRequestNotifications: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    SectionCard(
        title = "Background reliability",
        description = "The server runs in a foreground service. Android may still kill it unless the app " +
            "is exempt from battery optimisations.",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.notificationsGranted) "Notifications allowed" else "Allow notifications to see server status",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRequestNotifications, enabled = !state.notificationsGranted) { Text("Allow") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BatterySaver, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.ignoringBatteryOptimizations) "Battery optimisation is off for the app" else "Battery optimisation may stop the server",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenBatterySettings, enabled = !state.ignoringBatteryOptimizations) {
                Text("Disable")
            }
        }
    }
}

@Composable
private fun LogsPreview(logs: List<String>, onOpenLogs: () -> Unit, onClear: () -> Unit) {
    val listState = rememberLazyListState()
    SectionCard(title = "Recent log output", description = "Full log: Setup → Logs (also stored on disk).") {
        if (logs.isEmpty()) {
            Text("No output yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LaunchedEffect(logs.size) {
                runCatching { listState.animateScrollToItem((logs.size - 1).coerceAtLeast(0)) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(220.dp),
            ) {
                items(logs) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenLogs) { Text("Open logs") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun phaseLabel(server: LocalServerState): String = when (server.phase) {
    LocalServerState.Phase.STOPPED -> "Stopped"
    LocalServerState.Phase.STARTING -> "Starting…"
    LocalServerState.Phase.READY -> if (server.healthy) "Running (healthy)" else "Running (unhealthy)"
    LocalServerState.Phase.STOPPING -> "Stopping…"
    LocalServerState.Phase.FAILED -> "Failed"
}
