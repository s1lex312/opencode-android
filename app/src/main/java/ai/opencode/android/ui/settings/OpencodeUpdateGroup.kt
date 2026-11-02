package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.update.OpencodeUpdateState.Phase
import ai.opencode.android.settings.AppSettings
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsSwitchItem
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** "opencode on this phone": installed vs latest version, manual check/install, auto-update toggle. */
@Composable
fun OpencodeUpdateGroup(container: AppContainer) {
    val settings by container.settingsRepository.settings.collectAsState(initial = AppSettings())
    if (!settings.isLocalMode) return
    val updater = container.opencodeUpdater
    val state by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val installed = state.installed ?: updater.installedVersion()

    SettingsGroup(title = stringResource(R.string.update_group)) {
        SettingsItem(
            title = stringResource(R.string.update_installed, installed ?: "—"),
            subtitle = when (state.phase) {
                Phase.IDLE -> stringResource(R.string.update_idle)
                Phase.CHECKING -> stringResource(R.string.update_checking)
                Phase.UP_TO_DATE -> stringResource(R.string.update_up_to_date)
                Phase.AVAILABLE -> stringResource(R.string.update_available, state.latest.orEmpty())
                Phase.DOWNLOADING -> stringResource(R.string.update_downloading, (state.progress * 100).toInt())
                Phase.INSTALLING -> stringResource(R.string.update_installing)
                Phase.RESTARTING -> stringResource(R.string.update_restarting)
                Phase.DONE -> stringResource(R.string.update_done, state.installed.orEmpty())
                Phase.FAILED -> stringResource(R.string.update_failed, state.message.orEmpty())
            },
            icon = Icons.Default.SystemUpdate,
            trailing = {
                when (state.phase) {
                    Phase.AVAILABLE -> Button(onClick = updater::install) { Text(stringResource(R.string.update_install)) }
                    else -> TextButton(onClick = updater::check, enabled = !state.busy) { Text(stringResource(R.string.update_check)) }
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
            title = stringResource(R.string.update_auto),
            subtitle = stringResource(R.string.update_auto_hint),
            checked = settings.opencodeAutoUpdate,
            onCheckedChange = { enabled ->
                scope.launch { container.settingsRepository.update { it.copy(opencodeAutoUpdate = enabled) } }
            },
            icon = Icons.Default.Autorenew,
        )
    }
}
