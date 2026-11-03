package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.AppSettings
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsSwitchItem
import ai.opencode.android.ui.setup.formatBytes
import ai.opencode.android.update.AppUpdateState.Phase
import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** "App update": installed vs latest release, check / update buttons, automatic update switches. */
@Composable
fun AppUpdateGroup(container: AppContainer) {
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    val updater = container.appUpdater
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    SettingsGroup(title = stringResource(R.string.app_update_group)) {
        SettingsItem(
            title = stringResource(R.string.app_update_current, state.current),
            subtitle = when (state.phase) {
                Phase.IDLE -> stringResource(R.string.app_update_idle)
                Phase.CHECKING -> stringResource(R.string.app_update_checking)
                Phase.UP_TO_DATE -> stringResource(R.string.app_update_up_to_date)
                Phase.AVAILABLE -> stringResource(R.string.app_update_available, state.latest.orEmpty(), formatBytes(state.sizeBytes))
                Phase.DOWNLOADING -> stringResource(R.string.app_update_downloading, (state.progress * 100).toInt())
                Phase.READY -> stringResource(R.string.app_update_ready, state.latest.orEmpty())
                Phase.INSTALLING -> stringResource(R.string.app_update_installing)
                Phase.NEEDS_PERMISSION -> {
                    val base = stringResource(R.string.app_update_needs_permission)
                    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) "$base\n${stringResource(R.string.app_update_samsung_hint)}" else base
                }
                Phase.CONFIRMING -> stringResource(R.string.app_update_confirming, state.latest.orEmpty())
                Phase.SIGNATURE_MISMATCH -> stringResource(R.string.app_update_signature)
                Phase.FAILED -> stringResource(R.string.app_update_failed, state.message.orEmpty())
            },
            icon = Icons.Default.Update,
            trailing = {
                when (state.phase) {
                    Phase.AVAILABLE -> Button(onClick = updater::update) { Text(stringResource(R.string.app_update_download)) }
                    Phase.READY, Phase.CONFIRMING -> Button(onClick = updater::update) { Text(stringResource(R.string.app_update_install)) }
                    Phase.NEEDS_PERMISSION -> Button(onClick = {
                        // Already granted (the user flipped it and came back): install instead of
                        // sending them to the same settings screen again.
                        if (updater.canInstall()) updater.update() else runCatching { context.startActivity(updater.permissionIntent()) }
                    }) { Text(stringResource(R.string.app_update_allow)) }

                    else -> TextButton(onClick = updater::check, enabled = !state.busy) { Text(stringResource(R.string.app_update_check)) }
                }
            },
        )
        if (state.phase == Phase.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        SettingsDivider()
        SettingsSwitchItem(
            title = stringResource(R.string.app_update_auto),
            subtitle = stringResource(R.string.app_update_auto_hint),
            checked = settings.appAutoUpdate,
            onCheckedChange = { enabled -> scope.launch { container.settingsRepository.update { it.copy(appAutoUpdate = enabled) } } },
            icon = Icons.Default.Autorenew,
        )
        SettingsDivider()
        SettingsSwitchItem(
            title = stringResource(R.string.app_update_auto_install),
            subtitle = stringResource(R.string.app_update_auto_install_hint),
            checked = settings.appAutoInstall,
            enabled = settings.appAutoUpdate,
            onCheckedChange = { enabled -> scope.launch { container.settingsRepository.update { it.copy(appAutoInstall = enabled) } } },
            icon = Icons.Default.InstallMobile,
        )
    }
}
