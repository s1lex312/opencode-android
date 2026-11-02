package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalServerService
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.settings.AuthMode
import ai.opencode.android.settings.ConnectionMode
import ai.opencode.android.ui.components.IconBadge
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsSwitchItem
import ai.opencode.android.ui.components.StatusDot
import ai.opencode.android.ui.components.connectionLabel
import ai.opencode.android.ui.theme.StatusColors
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Settings → Connection: where opencode runs. LOCAL shows the built-in server with links to the
 * environment setup and its log; REMOTE shows the address, sign-in and a connection test.
 */
@Composable
fun ConnectionSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    MessageSnackbar(state.message, snackbarHostState, viewModel::dismissMessage)

    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // Start regardless of the answer: the foreground service works, only its notification may be hidden.
        LocalServerService.start(context)
        viewModel.startLocalServer()
    }
    val startLocalServer: () -> Unit = {
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
    val stopLocalServer: () -> Unit = {
        LocalServerService.stop(context)
        viewModel.stopLocalServer()
    }

    SettingsPage(
        title = stringResource(R.string.settings_connection_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) {
        ModePicker(selected = state.connectionMode, onSelect = viewModel::setConnectionMode)
        Text(
            text = stringResource(
                if (state.isLocalMode) R.string.settings_mode_local_hint else R.string.settings_mode_remote_hint,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        Crossfade(targetState = state.isLocalMode, label = "connection-mode") { local ->
            Column {
                if (local) {
                    LocalSection(
                        state = state,
                        onStart = startLocalServer,
                        onStop = stopLocalServer,
                        onOpenSetup = onOpenSetup,
                        onOpenLogs = onOpenLogs,
                    )
                } else {
                    RemoteSection(state = state, viewModel = viewModel)
                }
            }
        }

        ConnectionStatusLine(
            connection = state.connection,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun ModePicker(selected: ConnectionMode, onSelect: (ConnectionMode) -> Unit) {
    val modes = ConnectionMode.entries
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(modeLabel(mode)) },
            )
        }
    }
}

// ---------------------------------------------------------------------------------- local

@Composable
private fun LocalSection(
    state: SettingsUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.settings_local_group)) {
        LocalServerRow(
            local = state.localServer,
            starting = state.runtime.starting,
            address = state.settings.localServer.url.removePrefix("http://"),
            detail = when {
                state.runtime.needsSetup -> stringResource(R.string.settings_local_needs_setup)
                else -> state.runtime.lastError ?: state.localServer.message
            },
            onStart = onStart,
            onStop = onStop,
        )
        SettingsDivider()
        SettingsItem(
            title = stringResource(R.string.settings_environment),
            subtitle = when {
                !state.envChecked -> stringResource(R.string.settings_environment_checking)
                state.envVersion != null -> stringResource(R.string.settings_environment_installed, state.envVersion)
                else -> stringResource(R.string.settings_environment_missing)
            },
            icon = Icons.Default.Inventory2,
            showChevron = true,
            onClick = onOpenSetup,
        )
        SettingsDivider()
        SettingsItem(
            title = stringResource(R.string.settings_server_log),
            subtitle = stringResource(R.string.settings_server_log_subtitle),
            icon = Icons.AutoMirrored.Filled.Article,
            showChevron = true,
            onClick = onOpenLogs,
        )
    }
}

@Composable
private fun LocalServerRow(
    local: LocalServerState,
    starting: Boolean,
    address: String,
    detail: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val (status, color) = localStatus(local, starting, address)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Default.Terminal)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.settings_local_server), style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = color, size = 8.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            local.running -> OutlinedButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            local.phase == LocalServerState.Phase.STOPPING || starting -> OutlinedButton(onClick = {}, enabled = false) {
                Text(stringResource(R.string.action_start))
            }
            else -> FilledTonalButton(onClick = onStart) { Text(stringResource(R.string.action_start)) }
        }
    }
}

@Composable
private fun localStatus(local: LocalServerState, starting: Boolean, address: String): Pair<String, Color> = when {
    local.phase == LocalServerState.Phase.READY && local.healthy ->
        stringResource(R.string.settings_local_running, address) to StatusColors.ok
    local.phase == LocalServerState.Phase.READY ->
        stringResource(R.string.settings_local_unhealthy) to StatusColors.warn
    local.phase == LocalServerState.Phase.STARTING || starting ->
        stringResource(R.string.settings_local_starting) to StatusColors.warn
    local.phase == LocalServerState.Phase.STOPPING ->
        stringResource(R.string.settings_local_stopping) to StatusColors.warn
    local.phase == LocalServerState.Phase.FAILED ->
        stringResource(R.string.settings_local_failed) to MaterialTheme.colorScheme.error
    else -> stringResource(R.string.settings_local_stopped) to MaterialTheme.colorScheme.outline
}

// ---------------------------------------------------------------------------------- remote

@Composable
private fun RemoteSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val focusManager = LocalFocusManager.current
    val urlError = state.urlError?.resolve()

    SettingsGroup(title = stringResource(R.string.settings_remote_group)) {
        OutlinedTextField(
            value = state.draftServerUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text(stringResource(R.string.settings_remote_url_label)) },
            placeholder = { Text(stringResource(R.string.settings_remote_url_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
            singleLine = true,
            isError = urlError != null,
            supportingText = if (urlError != null) {
                { Text(urlError) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        SettingsDivider()
        SettingsSwitchItem(
            title = stringResource(R.string.settings_allow_http),
            subtitle = stringResource(R.string.settings_allow_http_subtitle),
            icon = Icons.Default.Lan,
            checked = state.allowLanCleartext,
            onCheckedChange = viewModel::setAllowLanCleartext,
        )
    }

    SettingsGroup(title = stringResource(R.string.settings_auth_group)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AuthModePicker(selected = state.authMode, onSelect = viewModel::setAuthMode)
            Text(
                text = stringResource(
                    when (state.authMode) {
                        AuthMode.NONE -> R.string.settings_auth_none_hint
                        AuthMode.BASIC -> R.string.settings_auth_password_hint
                        AuthMode.BEARER -> R.string.settings_auth_token_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state.authMode) {
                AuthMode.BASIC -> {
                    OutlinedTextField(
                        value = state.draftUsername,
                        onValueChange = viewModel::onUsernameChange,
                        label = { Text(stringResource(R.string.settings_username)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretField(
                        value = state.draftPassword,
                        onValueChange = viewModel::onPasswordChange,
                        label = stringResource(R.string.settings_password),
                        stored = state.passwordSet,
                        onDone = { focusManager.clearFocus() },
                    )
                }

                AuthMode.BEARER -> SecretField(
                    value = state.draftToken,
                    onValueChange = viewModel::onTokenChange,
                    label = stringResource(R.string.settings_token),
                    stored = state.tokenSet,
                    onDone = { focusManager.clearFocus() },
                )

                AuthMode.NONE -> Unit
            }
            if (!state.secretsPersistent && state.authMode != AuthMode.NONE) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_storage_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state.hasStoredSecrets) {
                TextButton(onClick = viewModel::clearCredentials) {
                    Text(stringResource(R.string.settings_clear_credentials))
                }
            }
        }
    }

    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.testResult?.let { result ->
            Text(
                text = result.text.resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (result.ok) StatusColors.ok else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.testConnection()
                },
                enabled = !state.testing,
                modifier = Modifier.weight(1f),
            ) {
                if (state.testing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_test))
            }
            Button(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.saveConnection()
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.settings_save_connect))
            }
        }
    }
}

@Composable
private fun AuthModePicker(selected: AuthMode, onSelect: (AuthMode) -> Unit) {
    val modes = AuthMode.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = {
                    Text(
                        stringResource(
                            when (mode) {
                                AuthMode.NONE -> R.string.settings_auth_none
                                AuthMode.BASIC -> R.string.settings_auth_password
                                AuthMode.BEARER -> R.string.settings_auth_token
                            },
                        ),
                    )
                },
            )
        }
    }
}

/** Masked field for a password or token, with a show/hide toggle and a "stored" hint. */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    stored: Boolean,
    onDone: () -> Unit,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        supportingText = if (stored) {
            { Text(stringResource(R.string.settings_secret_stored)) }
        } else {
            null
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.settings_hide_secret else R.string.settings_show_secret,
                    ),
                )
            }
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Live state of the app's HTTP connection, in words, under both modes. */
@Composable
private fun ConnectionStatusLine(connection: ConnectionState, modifier: Modifier = Modifier) {
    val (_, color) = connectionLabel(connection)
    val text = when (connection) {
        is ConnectionState.Idle -> stringResource(R.string.settings_status_idle)
        is ConnectionState.Connecting -> stringResource(R.string.settings_status_connecting)
        is ConnectionState.Connected -> stringResource(R.string.settings_status_connected, connection.baseUrl)
        is ConnectionState.Reconnecting -> stringResource(R.string.settings_status_reconnecting, connection.attempt)
        is ConnectionState.Failed -> stringResource(R.string.settings_status_failed, connection.message)
    }
    val hint = (connection as? ConnectionState.Failed)?.hint
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = color, size = 8.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!hint.isNullOrBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
