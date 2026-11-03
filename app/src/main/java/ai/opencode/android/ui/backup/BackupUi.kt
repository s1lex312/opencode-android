package ai.opencode.android.ui.backup

import ai.opencode.android.R
import ai.opencode.android.data.backup.BackupSnapshot
import ai.opencode.android.data.backup.ConfigBackup
import ai.opencode.android.data.backup.RestoreResult
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsSwitchItem
import ai.opencode.android.ui.format.relativeTime
import ai.opencode.android.ui.settings.SettingsPage
import ai.opencode.android.update.AppUpdateState
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Open a backup with read+write access, so the picked file keeps receiving the automatic copy. */
private class OpenBackupDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            // Start in Download/opencode where the automatic copy lives (ignored where unsupported).
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/${ConfigBackup.FOLDER}"),
            )
        }
}

private class CreateBackupDocument : ActivityResultContracts.CreateDocument(ConfigBackup.MIME) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

/** Relaunches the app from scratch so restored settings, secrets and server config all apply. */
fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ?: return
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

/**
 * Pick a backup file → (password if keys are sealed) → restore → restart. Returns the launcher;
 * [onCancelled] runs when the picker is dismissed without a file.
 */
@Composable
fun rememberRestoreFromFile(container: AppContainer, onCancelled: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = container.configBackup
    var picked by remember { mutableStateOf<Pair<Uri, BackupSnapshot>?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var wrongPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<RestoreResult.Ok?>(null) }

    fun run(password: String?, skipSecrets: Boolean) {
        val (uri, snapshot) = picked ?: return
        scope.launch {
            busy = true
            val result = backup.restore(snapshot, password, skipSecrets, source = uri)
            busy = false
            when (result) {
                RestoreResult.NeedsPassword -> askPassword = true
                RestoreResult.WrongPassword -> {
                    askPassword = true
                    wrongPassword = true
                }
                is RestoreResult.Ok -> {
                    askPassword = false
                    done = result
                }
                is RestoreResult.Failed -> {
                    askPassword = false
                    error = result.message
                }
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(OpenBackupDocument()) { uri ->
        if (uri == null) {
            onCancelled()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            backup.read(uri).fold(
                onSuccess = { snapshot ->
                    picked = uri to snapshot
                    wrongPassword = false
                    if (snapshot.needsPassword) askPassword = true else run(null, skipSecrets = false)
                },
                onFailure = { error = it.message ?: it.javaClass.simpleName },
            )
        }
    }

    if (askPassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { if (!busy) askPassword = false },
            title = { Text(stringResource(R.string.restore_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.restore_password_text))
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            wrongPassword = false
                        },
                        label = { Text(stringResource(R.string.backup_password_field)) },
                        singleLine = true,
                        isError = wrongPassword,
                        supportingText = if (wrongPassword) {
                            { Text(stringResource(R.string.restore_password_wrong)) }
                        } else {
                            null
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty() && !busy, onClick = { run(password, skipSecrets = false) }) {
                    Text(stringResource(R.string.restore_action))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { run(null, skipSecrets = true) }) {
                    Text(stringResource(R.string.restore_without_keys))
                }
            },
        )
    }

    if (busy && !askPassword) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.restore_working)) },
            text = { CircularProgressIndicator(Modifier.size(32.dp)) },
        )
    }

    done?.let { result ->
        AlertDialog(
            onDismissRequest = { restartApp(context) },
            title = { Text(stringResource(R.string.restore_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.restore_done_text))
                    if (result.secretsSkipped) Text(stringResource(R.string.restore_done_no_keys), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = { restartApp(context) }) { Text(stringResource(R.string.restart_app)) } },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            text = { Text(stringResource(R.string.restore_failed, message)) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }

    return { launcher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }
}

/**
 * App-wide prompts shown over any screen: the restore offer on a fresh install, the notice after
 * Android restored settings from the cloud, and the one-time "allow installing updates" request.
 */
@Composable
fun BackupAndUpdatePrompts(container: AppContainer) {
    val backupState by container.configBackup.state.collectAsState()
    val context = LocalContext.current

    // Outside the `if`: the restore clears offerRestore, and its "restart" dialog must outlive that.
    var choosing by remember { mutableStateOf(false) }
    val pick = rememberRestoreFromFile(container, onCancelled = { choosing = false })
    if (backupState.offerRestore) {
        if (!choosing) {
            AlertDialog(
                onDismissRequest = {},
                icon = { androidx.compose.material3.Icon(Icons.Default.Restore, contentDescription = null) },
                title = { Text(stringResource(R.string.restore_offer_title)) },
                text = { Text(stringResource(R.string.restore_offer_text)) },
                confirmButton = {
                    TextButton(onClick = {
                        choosing = true
                        pick()
                    }) { Text(stringResource(R.string.restore_offer_pick)) }
                },
                dismissButton = {
                    TextButton(onClick = container.configBackup::dismissOffer) { Text(stringResource(R.string.restore_offer_skip)) }
                },
            )
        }
    }

    if (backupState.restoredFromCloud) {
        AlertDialog(
            onDismissRequest = container.configBackup::acknowledgeCloudRestore,
            icon = { androidx.compose.material3.Icon(Icons.Default.CloudDone, contentDescription = null) },
            title = { Text(stringResource(R.string.restore_cloud_title)) },
            text = { Text(stringResource(R.string.restore_cloud_text)) },
            confirmButton = {
                TextButton(onClick = container.configBackup::acknowledgeCloudRestore) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }

    val update by container.appUpdater.state.collectAsState()
    var permissionAsked by remember { mutableStateOf(false) }
    if (update.phase == AppUpdateState.Phase.NEEDS_PERMISSION && !permissionAsked) {
        AlertDialog(
            onDismissRequest = { permissionAsked = true },
            title = { Text(stringResource(R.string.app_update_permission_title)) },
            text = { Text(stringResource(R.string.app_update_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    permissionAsked = true
                    runCatching { context.startActivity(container.appUpdater.permissionIntent()) }
                }) { Text(stringResource(R.string.app_update_allow)) }
            },
            dismissButton = { TextButton(onClick = { permissionAsked = true }) { Text(stringResource(R.string.app_update_later)) } },
        )
    }
}

@Composable
fun BackupScreen(container: AppContainer, onBack: () -> Unit) {
    val backup = container.configBackup
    val state by backup.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val restore = rememberRestoreFromFile(container)
    val saved = stringResource(R.string.backup_saved)
    val failed = stringResource(R.string.backup_save_failed)
    var editPassword by remember { mutableStateOf(false) }

    val saveAs = rememberLauncherForActivityResult(CreateBackupDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = backup.adoptTarget(uri)
                Toast.makeText(context, if (ok) saved else failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    SettingsPage(title = stringResource(R.string.backup_title), onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.backup_group_file)) {
            val status = when {
                state.error == ConfigBackup.NO_STORAGE_ACCESS && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                    stringResource(R.string.backup_no_storage)
                state.error != null -> stringResource(R.string.backup_auto_error, state.error.orEmpty())
                state.lastAt > 0 -> stringResource(R.string.backup_auto_saved, state.location.orEmpty(), relativeTime(state.lastAt))
                else -> stringResource(R.string.backup_auto_never)
            }
            SettingsSwitchItem(
                title = stringResource(R.string.backup_auto),
                subtitle = status,
                checked = state.auto,
                onCheckedChange = backup::setAuto,
                icon = Icons.Default.Backup,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.backup_save_now),
                icon = Icons.Default.Save,
                enabled = !state.busy,
                onClick = {
                    scope.launch {
                        val ok = backup.backupNow(force = true)
                        Toast.makeText(context, if (ok) saved else failed, Toast.LENGTH_SHORT).show()
                    }
                },
                trailing = if (state.busy) {
                    { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.backup_password),
                subtitle = stringResource(if (state.encrypted) R.string.backup_password_on else R.string.backup_password_off),
                icon = Icons.Default.Key,
                onClick = { editPassword = true },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.backup_save_as),
                subtitle = stringResource(R.string.backup_save_as_hint),
                icon = Icons.Default.SaveAs,
                onClick = { saveAs.launch(ConfigBackup.FILE_NAME) },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.backup_restore),
                subtitle = stringResource(R.string.backup_restore_hint),
                icon = Icons.Default.Restore,
                onClick = restore,
            )
        }

        SettingsGroup(title = stringResource(R.string.backup_group_cloud)) {
            SettingsItem(
                title = stringResource(R.string.backup_cloud),
                subtitle = stringResource(R.string.backup_cloud_hint),
                icon = Icons.Default.CloudDone,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.backup_what),
                subtitle = stringResource(R.string.backup_what_hint),
                icon = Icons.Default.Info,
            )
        }
    }

    if (editPassword) {
        BackupPasswordDialog(
            hasPassword = state.encrypted,
            onDismiss = { editPassword = false },
            onSave = {
                backup.setPassword(it)
                editPassword = false
            },
        )
    }
}

@Composable
private fun BackupPasswordDialog(hasPassword: Boolean, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD
    val mismatch = repeat.isNotEmpty() && repeat != password
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_password_text))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_password_field)) },
                    singleLine = true,
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        { Text(stringResource(R.string.backup_password_short)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = { Text(stringResource(R.string.backup_password_repeat)) },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.backup_password_mismatch)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.length >= MIN_PASSWORD && password == repeat,
                onClick = { onSave(password) },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            if (hasPassword) {
                TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.backup_password_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

private const val MIN_PASSWORD = 6
