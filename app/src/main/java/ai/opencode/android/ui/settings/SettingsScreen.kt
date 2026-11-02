package ai.opencode.android.ui.settings

import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalServerService
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.settings.AuthMode
import ai.opencode.android.ui.components.AdaptiveContent
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.components.SectionCard
import ai.opencode.android.ui.components.SwitchRow
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var passwordDraft by rememberSaveable { mutableStateOf("") }
    var tokenDraft by rememberSaveable { mutableStateOf("") }
    var showModelDialog by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // Start regardless of the answer: the foreground service works, only its notification may be hidden.
        LocalServerService.start(context)
        viewModel.startLocalServer()
    }

    val startLocalServer = {
        val missingPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (missingPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            LocalServerService.start(context)
            viewModel.startLocalServer()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AdaptiveContent {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item { ConnectionSection(state, viewModel) }
                    item { AuthenticationSection(state, viewModel, passwordDraft, tokenDraft, { passwordDraft = it }, { tokenDraft = it }) }
                    item { ModelSection(state, viewModel, onOpenPicker = { showModelDialog = true }) }
                    item { PermissionsSection(state, viewModel) }
                    item { LocalServerSection(state, viewModel, onStart = startLocalServer) }
                    item { AboutSection(state) }
                }
            }
        }
    }

    if (showModelDialog) {
        ModelPickerDialog(
            state = state,
            onDismiss = { showModelDialog = false },
            onSelect = { option ->
                viewModel.selectModel(option)
                showModelDialog = false
            },
            onUseDefault = {
                viewModel.clearModel()
                showModelDialog = false
            },
            onReload = viewModel::loadModels,
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            text = { Text(message) },
            title = { Text("Saved") },
        )
    }
}

@Composable
private fun ConnectionSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(
        title = "Server connection",
        description = "Point the app at a machine running `opencode serve`. HTTPS is required for public " +
            "hosts; plain HTTP is only accepted for localhost and (optionally) your local network.",
    ) {
        OutlinedTextField(
            value = state.draftServerUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text("Server address") },
            placeholder = { Text("http://192.168.1.10:4096") },
            singleLine = true,
            isError = state.urlError != null,
            supportingText = state.urlError?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.draftUsername,
            onValueChange = viewModel::onUsernameChange,
            label = { Text("Basic auth username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.draftAgent,
            onValueChange = viewModel::onAgentChange,
            label = { Text("Agent (optional)") },
            placeholder = { Text("build") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::saveConnection) { Text("Save & connect") }
            OutlinedButton(onClick = viewModel::testConnection, enabled = !state.testing) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Test")
                }
            }
        }
        state.testMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider()
        Text(state.connectionStatus, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow(
            title = "Connect automatically",
            subtitle = "Connect on app start using the saved address",
            checked = state.autoConnect,
            onCheckedChange = viewModel::setAutoConnect,
        )
        SwitchRow(
            title = "Allow cleartext HTTP on the local network",
            subtitle = "Needed for http://192.168.x.x and http://10.x.x.x. Public hosts always require HTTPS.",
            checked = state.allowLanCleartext,
            onCheckedChange = viewModel::setAllowLanCleartext,
        )
    }
}

@Composable
private fun AuthenticationSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    passwordDraft: String,
    tokenDraft: String,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
) {
    SectionCard(
        title = "Authentication",
        description = "opencode serve accepts HTTP Basic auth (username opencode + password) or a bearer token. " +
            "Secrets are encrypted with a Keystore-backed AES-256-GCM key and never written to logs.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.authMode == mode,
                    onClick = { viewModel.setAuthMode(mode) },
                    label = {
                        Text(
                            when (mode) {
                                AuthMode.NONE -> "None"
                                AuthMode.BASIC -> "Password"
                                AuthMode.BEARER -> "Token"
                            },
                        )
                    },
                )
            }
        }

        if (state.authMode != AuthMode.NONE) {
            if (state.authMode == AuthMode.BASIC) {
                OutlinedTextField(
                    value = passwordDraft,
                    onValueChange = onPasswordChange,
                    label = { Text(if (state.passwordSet) "Password (stored — enter to replace)" else "Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.savePassword(passwordDraft)
                        onPasswordChange("")
                    }) { Text("Save password") }
                    if (state.passwordSet) {
                        OutlinedButton(onClick = { viewModel.savePassword("") }) { Text("Clear") }
                    }
                }
            } else {
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = onTokenChange,
                    label = { Text(if (state.tokenSet) "Token (stored — enter to replace)" else "Bearer token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.saveToken(tokenDraft)
                        onTokenChange("")
                    }) { Text("Save token") }
                    if (state.tokenSet) {
                        OutlinedButton(onClick = { viewModel.saveToken("") }) { Text("Clear") }
                    }
                }
            }
            if (!state.secretsPersistent) {
                Text(
                    text = "Encrypted storage is unavailable on this device, so secrets are kept in memory only " +
                        "and will be lost when the app exits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = viewModel::clearSecrets) { Text("Clear all stored credentials") }
        }
    }
}

@Composable
private fun ModelSection(state: SettingsUiState, viewModel: SettingsViewModel, onOpenPicker: () -> Unit) {
    SectionCard(
        title = "Model",
        description = "The catalogue comes from the server. Selecting a model pins it for new prompts.",
    ) {
        val selected = state.selectedModel
        Text(
            text = if (selected == null) "Using the server default model" else "${selected.providerID} / ${selected.modelID}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenPicker) { Text("Choose model") }
            OutlinedButton(onClick = viewModel::loadModels, enabled = !state.modelsLoading) {
                Text(if (state.modelsLoading) "Loading…" else "Reload list")
            }
        }
        state.modelsError?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (state.modelsLoaded) {
            Text(
                text = "${state.models.size} model(s) available",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionsSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(
        title = "Permission policy",
        description = "How the app answers the agent's tool-approval requests while you are away from the screen.",
    ) {
        PermissionPolicy.entries.forEach { policy ->
            val (title, description) = when (policy) {
                PermissionPolicy.ASK -> "Ask me" to "Show an approval card in the chat"
                PermissionPolicy.AUTO_ALLOW -> "Auto-allow once" to "Approve each request automatically (still visible in the transcript)"
                PermissionPolicy.DENY -> "Deny" to "Reject every request automatically"
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { viewModel.setPermissionPolicy(policy) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.permissionPolicy == policy,
                    onClick = { viewModel.setPermissionPolicy(policy) },
                )
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalServerSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    SectionCard(
        title = "Local server (experimental)",
        description = "Run opencode inside a proot rootfs on this device, no root required. " +
            "The APK ships no proot binary or rootfs — provision them as described in server/README.md.",
    ) {
        val local = state.localServer
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (local.phase) {
                        LocalServerState.Phase.STOPPED -> "Stopped"
                        LocalServerState.Phase.STARTING -> "Starting…"
                        LocalServerState.Phase.READY -> if (local.healthy) "Running (healthy)" else "Running (unhealthy)"
                        LocalServerState.Phase.FAILED -> "Failed"
                        LocalServerState.Phase.STOPPING -> "Stopping…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                local.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (local.running) {
                OutlinedButton(onClick = {
                    LocalServerService.stop(context)
                    viewModel.stopLocalServer()
                }) { Text("Stop") }
            } else {
                Button(onClick = onStart) { Text("Start") }
            }
        }

        OutlinedTextField(
            value = state.draftRootfs,
            onValueChange = viewModel::onRootfsChange,
            label = { Text("Rootfs path") },
            placeholder = { Text("/data/data/ai.opencode.android/files/rootfs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.draftProot,
            onValueChange = viewModel::onProotChange,
            label = { Text("proot path (optional)") },
            placeholder = { Text("defaults to the app's native lib dir") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.draftPort,
                onValueChange = viewModel::onPortChange,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
            OutlinedTextField(
                value = state.draftCommand,
                onValueChange = viewModel::onCommandChange,
                label = { Text("Server command") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::saveLocalServerSettings) { Text("Save local settings") }
            OutlinedButton(onClick = viewModel::clearLocalServerLogs) { Text("Clear log") }
        }
        if (local.logs.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = local.logs.takeLast(200).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AboutSection(state: SettingsUiState) {
    SectionCard(
        title = "About",
        description = "opencode for Android — a native client for the opencode coding agent server.",
    ) {
        Text(
            text = "Version 1.0.0 (arm64 recommended). API surface: opencode 2.x `/api` and legacy " +
                "0.x/1.x REST are both supported.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Stored secrets: ${if (state.secretsPersistent) "encrypted (Keystore)" else "memory only"}. " +
                "Traffic to public hosts is HTTPS-only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(2.dp))
        Text(
            text = "Known limitation: the embedded proot server is scaffolding, not a shippable runtime.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelPickerDialog(
    state: SettingsUiState,
    onDismiss: () -> Unit,
    onSelect: (ModelOption) -> Unit,
    onUseDefault: () -> Unit,
    onReload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select model") },
        text = {
            when {
                state.modelsLoading -> LoadingIndicator("Loading models…")
                state.models.isEmpty() -> Column {
                    Text(
                        text = state.modelsError ?: "The server returned no models. Connect first, then reload.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onReload) { Text("Reload") }
                }

                else -> LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(state.models, key = { "${it.providerID}/${it.modelID}" }) { option ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !option.disabled) { onSelect(option) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                text = option.modelID,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (option.disabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = option.providerID + if (option.disabled) " · disabled" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onUseDefault) { Text("Use default") } },
    )
}
