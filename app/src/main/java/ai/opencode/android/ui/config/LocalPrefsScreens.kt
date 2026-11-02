package ai.opencode.android.ui.config

import ai.opencode.android.R
import ai.opencode.android.bridge.StorageAccess
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.ChatSettings
import ai.opencode.android.settings.ChatStyle
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsRadioItem
import ai.opencode.android.ui.components.SettingsSwitchItem
import ai.opencode.android.ui.settings.SettingsPage
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/** Settings → Chat: how the transcript and the composer look and behave on this device. */
@Composable
fun ChatSettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenAppearance: () -> Unit) {
    val viewModel: LocalPrefsViewModel = viewModel(factory = LocalPrefsViewModel.factory(container))
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chat = settings.chat
    val update: ((ChatSettings) -> ChatSettings) -> Unit = { transform -> viewModel.update { it.copy(chat = transform(it.chat)) } }

    SettingsPage(title = stringResource(R.string.chat_settings_title), onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.chat_settings_style)) {
            SettingsRadioItem(
                title = stringResource(R.string.chat_style_claude),
                subtitle = stringResource(R.string.chat_style_claude_hint),
                selected = chat.style == ChatStyle.CLAUDE_CODE,
                onClick = { update { it.copy(style = ChatStyle.CLAUDE_CODE) } },
            )
            SettingsRadioItem(
                title = stringResource(R.string.chat_style_bubbles),
                subtitle = stringResource(R.string.chat_style_bubbles_hint),
                selected = chat.style == ChatStyle.BUBBLES,
                onClick = { update { it.copy(style = ChatStyle.BUBBLES) } },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.chat_settings_monospace),
                subtitle = stringResource(R.string.chat_settings_monospace_hint),
                icon = Icons.Default.Code,
                checked = chat.monospace,
                onCheckedChange = { value -> update { it.copy(monospace = value) } },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_appearance),
                subtitle = stringResource(R.string.chat_settings_appearance_hint),
                icon = Icons.Default.Palette,
                showChevron = true,
                onClick = onOpenAppearance,
            )
        }

        SettingsGroup(title = stringResource(R.string.chat_settings_transcript)) {
            SettingsSwitchItem(
                title = stringResource(R.string.chat_settings_reasoning),
                subtitle = stringResource(R.string.chat_settings_reasoning_hint),
                icon = Icons.Default.Psychology,
                checked = chat.expandReasoning,
                onCheckedChange = { value -> update { it.copy(expandReasoning = value) } },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.chat_settings_tools),
                subtitle = stringResource(R.string.chat_settings_tools_hint),
                icon = Icons.Default.Build,
                checked = chat.expandTools,
                onCheckedChange = { value -> update { it.copy(expandTools = value) } },
            )
            SettingsDivider()
            SliderRow(
                title = stringResource(R.string.chat_settings_tool_lines),
                value = chat.toolPreviewLines,
                range = 0..12,
                label = { if (it == 0) stringResource(R.string.chat_settings_off) else it.toString() },
                onChange = { value -> update { it.copy(toolPreviewLines = value) } },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.chat_settings_timestamps),
                icon = Icons.Default.Schedule,
                checked = chat.showTimestamps,
                onCheckedChange = { value -> update { it.copy(showTimestamps = value) } },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.chat_settings_cost),
                checked = chat.showCost,
                onCheckedChange = { value -> update { it.copy(showCost = value) } },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.chat_settings_playful),
                subtitle = stringResource(R.string.chat_settings_playful_hint),
                checked = chat.playfulSpinner,
                onCheckedChange = { value -> update { it.copy(playfulSpinner = value) } },
            )
        }

        SettingsGroup(title = stringResource(R.string.chat_settings_input)) {
            SettingsSwitchItem(
                title = stringResource(R.string.chat_settings_send_enter),
                subtitle = stringResource(R.string.chat_settings_send_enter_hint),
                icon = Icons.Default.TouchApp,
                checked = chat.sendOnEnter,
                onCheckedChange = { value -> update { it.copy(sendOnEnter = value) } },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.chat_settings_haptics),
                icon = Icons.Default.Vibration,
                checked = chat.haptics,
                onCheckedChange = { value -> update { it.copy(haptics = value) } },
            )
        }

        SettingsGroup(title = stringResource(R.string.chat_settings_context)) {
            SliderRow(
                title = stringResource(R.string.chat_settings_warn),
                value = chat.contextWarnPercent,
                range = 30..95,
                label = { "$it%" },
                onChange = { value -> update { it.copy(contextWarnPercent = value, contextDangerPercent = maxOf(it.contextDangerPercent, value + 1)) } },
            )
            SettingsDivider()
            SliderRow(
                title = stringResource(R.string.chat_settings_danger),
                value = chat.contextDangerPercent,
                range = 40..100,
                label = { "$it%" },
                onChange = { value -> update { it.copy(contextDangerPercent = value, contextWarnPercent = minOf(it.contextWarnPercent, value - 1)) } },
            )
            SettingsDivider()
            SliderRow(
                title = stringResource(R.string.chat_settings_auto_compact),
                subtitle = stringResource(R.string.chat_settings_auto_compact_hint),
                value = chat.autoCompactPercent,
                range = 0..95,
                label = { if (it == 0) stringResource(R.string.chat_settings_off) else "$it%" },
                onChange = { value -> update { it.copy(autoCompactPercent = if (value < 50) 0 else value) } },
            )
            if (settings.modelContextLimits.isNotEmpty() || settings.sessionContextLimits.isNotEmpty()) {
                SettingsDivider()
                SettingsItem(
                    title = stringResource(R.string.chat_settings_limits_reset),
                    subtitle = stringResource(
                        R.string.chat_settings_limits_summary,
                        settings.modelContextLimits.size,
                        settings.sessionContextLimits.size,
                    ),
                    icon = Icons.Default.RestartAlt,
                    onClick = { viewModel.update { it.copy(modelContextLimits = emptyMap(), sessionContextLimits = emptyMap()) } },
                )
            }
        }
    }
}

@Composable
internal fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    label: @Composable (Int) -> String,
    onChange: (Int) -> Unit,
    subtitle: String? = null,
) {
    // Local state keeps dragging smooth; the store is written when the finger lifts.
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label(current.roundToInt()), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
        }
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onChange(current.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** Settings → Phone access: shared storage in the Linux environment and the phone tools MCP server. */
@Composable
fun PhoneAccessScreen(container: AppContainer, onBack: () -> Unit, onOpenPermissions: () -> Unit, onOpenMcp: () -> Unit) {
    val viewModel: LocalPrefsViewModel = viewModel(factory = LocalPrefsViewModel.factory(container))
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val phone by viewModel.phone.collectAsStateWithLifecycle()
    val granted by viewModel.storageGranted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshGrant() }
    }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshGrant()
    }
    val requestStorage: () -> Unit = {
        val intent = StorageAccess.settingsIntent(context)
        if (intent != null) {
            runCatching { context.startActivity(intent) }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    SettingsPage(title = stringResource(R.string.phone_title), onBack = onBack) {
        Text(
            text = stringResource(if (settings.isLocalMode) R.string.phone_intro else R.string.phone_remote_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )

        SettingsGroup(title = stringResource(R.string.phone_storage_group)) {
            SettingsSwitchItem(
                title = stringResource(R.string.phone_storage),
                subtitle = stringResource(R.string.phone_storage_hint),
                icon = Icons.Default.FolderOpen,
                checked = settings.phone.storage,
                enabled = settings.isLocalMode,
                onCheckedChange = { value ->
                    viewModel.update { it.copy(phone = it.phone.copy(storage = value)) }
                    if (value && !granted) requestStorage()
                },
            )
            if (settings.phone.storage) {
                SettingsDivider()
                SettingsItem(
                    title = stringResource(if (granted) R.string.phone_storage_granted else R.string.phone_storage_missing),
                    subtitle = stringResource(if (granted) R.string.phone_storage_paths else R.string.phone_storage_grant),
                    icon = Icons.Default.Security,
                    onClick = if (granted) null else requestStorage,
                )
                SettingsDivider()
                SettingsItem(
                    title = stringResource(R.string.phone_restart),
                    subtitle = stringResource(R.string.phone_restart_hint),
                    icon = Icons.Default.RestartAlt,
                    onClick = viewModel::restartLocalServer,
                )
            }
        }

        SettingsGroup(title = stringResource(R.string.phone_tools_group)) {
            SettingsSwitchItem(
                title = stringResource(R.string.phone_tools),
                subtitle = stringResource(R.string.phone_tools_hint),
                icon = Icons.Default.SmartToy,
                checked = settings.phone.androidTools,
                enabled = settings.isLocalMode,
                onCheckedChange = { value -> viewModel.update { it.copy(phone = it.phone.copy(androidTools = value)) } },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(
                    when {
                        !settings.phone.androidTools || !settings.isLocalMode -> R.string.phone_tools_off
                        phone.registered -> R.string.phone_tools_on
                        phone.toolsRunning -> R.string.phone_tools_waiting
                        else -> R.string.phone_tools_failed
                    },
                ),
                subtitle = phone.error ?: phone.port?.let { "127.0.0.1:$it/mcp · mcp.android" },
                icon = Icons.Default.Hub,
                showChevron = true,
                onClick = onOpenMcp,
            )
            Text(
                text = viewModel.toolNames.joinToString("  "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        SettingsGroup(title = stringResource(R.string.phone_freedom_group)) {
            SettingsItem(
                title = stringResource(R.string.behaviour_title),
                subtitle = stringResource(R.string.phone_freedom_hint),
                icon = Icons.Default.Security,
                showChevron = true,
                onClick = onOpenPermissions,
            )
        }
    }
}
