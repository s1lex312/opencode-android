package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.net.ProxyProbe
import ai.opencode.android.settings.ProxyScope
import ai.opencode.android.settings.ProxySettings
import ai.opencode.android.settings.ProxyType
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsRadioItem
import ai.opencode.android.ui.components.SettingsSwitchItem
import ai.opencode.android.ui.components.StatusDot
import ai.opencode.android.ui.theme.StatusColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Settings → Proxy: an HTTP or SOCKS5 upstream for providers that block the user's region.
 * Edits are kept in a draft until "Save"; the test calls the provider APIs the way the server would.
 */
@Composable
fun ProxyScreen(container: AppContainer, onBack: () -> Unit) {
    val controller = container.proxyController
    val state by controller.state.collectAsState()
    val settings by container.settingsRepository.settings.collectAsState(initial = null)
    val saved = settings?.proxy ?: return
    val localMode = settings?.isLocalMode == true
    val scope = rememberCoroutineScope()

    var draft by remember(saved) { mutableStateOf(saved) }
    var portText by rememberSaveable(saved) { mutableStateOf(if (saved.port > 0) saved.port.toString() else "") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordTouched by rememberSaveable { mutableStateOf(false) }
    var hostsText by rememberSaveable(saved) { mutableStateOf(saved.hosts.joinToString("\n")) }
    var bypassText by rememberSaveable(saved) { mutableStateOf(saved.bypass.joinToString("\n")) }
    val hasPassword = remember(state) { controller.hasPassword() }

    fun build(): ProxySettings = draft.copy(
        host = draft.host.trim().removePrefix("http://").removePrefix("https://").removePrefix("socks5://").trimEnd('/'),
        port = portText.trim().toIntOrNull() ?: 0,
        hosts = hostsText.lines().map { it.trim() }.filter { it.isNotEmpty() },
        bypass = bypassText.lines().map { it.trim() }.filter { it.isNotEmpty() },
    )
    val dirty = build() != saved || passwordTouched
    // The server reads the proxy variables when it starts; turning the proxy on or off needs a restart.
    val restartNeeded = localMode && state.serverPort != null &&
        state.serverPort != (if (saved.active && saved.applyToServer) state.bridgePort else 0)

    SettingsPage(title = stringResource(R.string.proxy_title), onBack = onBack) {
        Text(
            text = stringResource(R.string.proxy_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SettingsGroup {
            SettingsSwitchItem(
                title = stringResource(R.string.proxy_enabled),
                subtitle = when {
                    !saved.configured -> stringResource(R.string.proxy_not_configured)
                    state.bridgePort != 0 -> stringResource(R.string.proxy_running, state.bridgePort)
                    else -> stringResource(R.string.proxy_off)
                },
                icon = Icons.Default.VpnLock,
                checked = draft.enabled,
                onCheckedChange = { draft = draft.copy(enabled = it) },
            )
        }

        SettingsGroup(title = stringResource(R.string.proxy_server_group)) {
            ProxyType.entries.forEach { type ->
                SettingsRadioItem(
                    title = stringResource(if (type == ProxyType.HTTP) R.string.proxy_type_http else R.string.proxy_type_socks),
                    subtitle = stringResource(if (type == ProxyType.HTTP) R.string.proxy_type_http_hint else R.string.proxy_type_socks_hint),
                    selected = draft.type == type,
                    onClick = { draft = draft.copy(type = type) },
                )
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft.host,
                        onValueChange = { draft = draft.copy(host = it) },
                        label = { Text(stringResource(R.string.proxy_host)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { text -> portText = text.filter { it.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.proxy_port)) },
                        singleLine = true,
                        isError = portText.isNotEmpty() && portText.toIntOrNull() !in 1..65535,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                }
                OutlinedTextField(
                    value = draft.username,
                    onValueChange = { draft = draft.copy(username = it) },
                    label = { Text(stringResource(R.string.proxy_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        passwordTouched = true
                    },
                    label = { Text(stringResource(R.string.proxy_password)) },
                    placeholder = { if (hasPassword) Text(stringResource(R.string.proxy_password_kept)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.proxy_scope_group)) {
            SettingsRadioItem(
                title = stringResource(R.string.proxy_scope_listed),
                subtitle = stringResource(R.string.proxy_scope_listed_hint),
                selected = draft.scope == ProxyScope.LISTED,
                onClick = { draft = draft.copy(scope = ProxyScope.LISTED) },
            )
            SettingsRadioItem(
                title = stringResource(R.string.proxy_scope_all),
                subtitle = stringResource(R.string.proxy_scope_all_hint),
                selected = draft.scope == ProxyScope.ALL,
                onClick = { draft = draft.copy(scope = ProxyScope.ALL) },
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (draft.scope == ProxyScope.LISTED) {
                    OutlinedTextField(
                        value = hostsText,
                        onValueChange = { hostsText = it },
                        label = { Text(stringResource(R.string.proxy_hosts)) },
                        supportingText = { Text(stringResource(R.string.proxy_hosts_hint)) },
                        minLines = 4,
                        maxLines = 12,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { hostsText = ProxySettings.DEFAULT_HOSTS.joinToString("\n") }) {
                        Text(stringResource(R.string.proxy_hosts_reset))
                    }
                } else {
                    OutlinedTextField(
                        value = bypassText,
                        onValueChange = { bypassText = it },
                        label = { Text(stringResource(R.string.proxy_bypass)) },
                        supportingText = { Text(stringResource(R.string.proxy_hosts_hint)) },
                        minLines = 3,
                        maxLines = 10,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        SettingsGroup(title = stringResource(R.string.proxy_apply_group)) {
            SettingsSwitchItem(
                title = stringResource(R.string.proxy_apply_server),
                subtitle = stringResource(if (localMode) R.string.proxy_apply_server_hint else R.string.proxy_apply_server_remote),
                icon = Icons.Default.Dns,
                checked = draft.applyToServer,
                onCheckedChange = { draft = draft.copy(applyToServer = it) },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.proxy_apply_app),
                subtitle = stringResource(R.string.proxy_apply_app_hint),
                icon = Icons.Default.Apps,
                checked = draft.applyToApp,
                onCheckedChange = { draft = draft.copy(applyToApp = it) },
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = dirty,
                onClick = {
                    val value = build()
                    scope.launch {
                        if (passwordTouched) controller.setPassword(password)
                        controller.save(value)
                        password = ""
                        passwordTouched = false
                    }
                },
            ) { Text(stringResource(R.string.proxy_save)) }
            OutlinedButton(enabled = !state.testing && !dirty, onClick = controller::test) {
                if (state.testing) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).width(16.dp), strokeWidth = 2.dp)
                }
                Text(stringResource(R.string.proxy_test))
            }
        }
        if (dirty) {
            Text(
                text = stringResource(R.string.proxy_unsaved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        if (restartNeeded) {
            SettingsGroup {
                SettingsItem(
                    title = stringResource(R.string.proxy_restart),
                    subtitle = stringResource(R.string.proxy_restart_hint),
                    icon = Icons.Default.RestartAlt,
                    onClick = { container.restartLocalServerService() },
                )
            }
        }

        if (state.probes.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.proxy_test_results)) {
                state.probes.forEach { probe -> ProbeRow(probe) }
            }
        }
        Spacer(Modifier.padding(8.dp))
    }
}

@Composable
private fun ProbeRow(probe: ProxyProbe) {
    val color = when {
        probe.reachable -> StatusColors.ok
        probe.regionBlocked -> MaterialTheme.colorScheme.error
        else -> StatusColors.warn
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(probe.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = when {
                    probe.error != null -> stringResource(R.string.proxy_probe_error, probe.error)
                    probe.regionBlocked -> stringResource(R.string.proxy_probe_blocked, probe.millis)
                    probe.reachable -> stringResource(R.string.proxy_probe_ok, probe.code ?: 0, probe.millis)
                    else -> stringResource(R.string.proxy_probe_code, probe.code ?: 0, probe.millis)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(if (probe.proxied) R.string.proxy_probe_via else R.string.proxy_probe_direct),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
