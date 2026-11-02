package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.AppSettings
import ai.opencode.android.settings.AuthMode
import ai.opencode.android.settings.ConnectionMode
import ai.opencode.android.settings.ServerProfile
import ai.opencode.android.settings.ServerProfileManager
import ai.opencode.android.ui.components.ConnectionBadge
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID

/** Saved servers (this phone, a laptop, a VPS…) with one-tap switching. */
@Composable
fun ServerProfilesScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    val connection by container.connectionManager.state.collectAsState()
    val manager = container.serverProfiles
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ServerProfile?>(null) }
    val active = settings.activeServerProfile
    val switched = stringResource(R.string.servers_switched)

    SettingsPage(title = stringResource(R.string.servers_title), onBack = onBack) {
        Row(Modifier.padding(horizontal = 32.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.servers_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            ConnectionBadge(connection)
        }

        if (settings.serverProfiles.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.servers_saved)) {
                settings.serverProfiles.forEachIndexed { index, profile ->
                    if (index > 0) SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = profile.id == active?.id, role = Role.RadioButton) {
                                scope.launch {
                                    manager.activate(profile.id)
                                    Toast.makeText(context, switched.format(profile.name), Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = profile.id == active?.id, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Icon(
                            imageVector = if (profile.mode == ConnectionMode.LOCAL) Icons.Default.PhoneAndroid else Icons.Default.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (profile.mode == ConnectionMode.LOCAL) stringResource(R.string.servers_local) else profile.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        IconButton(onClick = { editing = profile }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                        }
                    }
                }
            }
        }

        Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active == null) {
                OutlinedButton(onClick = { scope.launch { manager.saveCurrent("") } }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.servers_save_current))
                }
            }
            OutlinedButton(
                onClick = { editing = ServerProfile(id = UUID.randomUUID().toString(), name = "", mode = ConnectionMode.REMOTE, url = "http://") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.servers_add))
            }
        }
    }

    editing?.let { profile ->
        val isNew = settings.serverProfiles.none { it.id == profile.id }
        ProfileEditor(
            initial = profile,
            isNew = isNew,
            hasPassword = manager.hasSecret(profile, ServerProfile.SECRET_PASSWORD),
            hasToken = manager.hasSecret(profile, ServerProfile.SECRET_TOKEN),
            onSave = { saved, password, token ->
                scope.launch {
                    when (val result = manager.save(saved, password, token)) {
                        is ServerProfileManager.SaveResult.Ok -> editing = null
                        is ServerProfileManager.SaveResult.InvalidUrl -> Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDelete = {
                editing = null
                scope.launch { manager.delete(profile.id) }
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ProfileEditor(
    initial: ServerProfile,
    isNew: Boolean,
    hasPassword: Boolean,
    hasToken: Boolean,
    onSave: (ServerProfile, String?, String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var mode by remember { mutableStateOf(initial.mode) }
    var url by remember { mutableStateOf(initial.url) }
    var authMode by remember { mutableStateOf(initial.authMode) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.servers_add else R.string.servers_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.servers_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == ConnectionMode.LOCAL, onClick = { mode = ConnectionMode.LOCAL }, label = { Text(stringResource(R.string.servers_local)) })
                    FilterChip(selected = mode == ConnectionMode.REMOTE, onClick = { mode = ConnectionMode.REMOTE }, label = { Text(stringResource(R.string.servers_remote)) })
                }
                if (mode == ConnectionMode.REMOTE) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.servers_url)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AuthMode.entries.forEach { option ->
                            FilterChip(
                                selected = authMode == option,
                                onClick = { authMode = option },
                                label = {
                                    Text(
                                        stringResource(
                                            when (option) {
                                                AuthMode.NONE -> R.string.servers_auth_none
                                                AuthMode.BASIC -> R.string.servers_auth_basic
                                                AuthMode.BEARER -> R.string.servers_auth_bearer
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    when (authMode) {
                        AuthMode.BASIC -> {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text(stringResource(R.string.servers_username)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.servers_password)) },
                                placeholder = { if (hasPassword) Text(stringResource(R.string.servers_secret_kept)) },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        AuthMode.BEARER -> OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            label = { Text(stringResource(R.string.servers_token)) },
                            placeholder = { if (hasToken) Text(stringResource(R.string.servers_secret_kept)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        AuthMode.NONE -> Unit
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val fallbackName = if (mode == ConnectionMode.LOCAL) "Phone" else url.substringAfter("://").substringBefore('/')
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { fallbackName },
                            mode = mode,
                            url = url.trim(),
                            authMode = authMode,
                            username = username.trim().ifBlank { AppSettings.DEFAULT_USERNAME },
                        ),
                        password.ifEmpty { null },
                        token.ifEmpty { null },
                    )
                },
                enabled = mode == ConnectionMode.LOCAL || url.trim().length > "http://".length,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
