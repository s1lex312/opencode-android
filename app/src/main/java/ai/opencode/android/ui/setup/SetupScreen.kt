package ai.opencode.android.ui.setup

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.server.provision.EmbeddedBundleStatus
import ai.opencode.android.server.provision.ProvisionState
import ai.opencode.android.settings.ConnectionMode
import ai.opencode.android.ui.components.AdaptiveContent
import ai.opencode.android.ui.components.ErrorCard
import ai.opencode.android.ui.components.IconBadge
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.StatusDot
import ai.opencode.android.ui.theme.StatusColors
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** MIME types offered by the system picker when importing an environment bundle. */
private val BUNDLE_MIME_TYPES = arrayOf("application/gzip", "application/x-gzip", "application/octet-stream")

/**
 * Onboarding / maintenance screen for "everything on the phone" mode.
 *
 * Reads top to bottom like a short wizard: what this is (hero), where we are (stepper), the one
 * thing to do next (primary button). Everything else — Android background restrictions, other
 * bundle sources, the log, maintenance and diagnostics — sits below in secondary sections.
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

    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var notificationsDenied by rememberSaveable { mutableStateOf(false) }
    // null = follow the default for the current state; the user's choice wins once they tap.
    var sourcesExpandedChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }

    val pickBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::installFromFile)
    }
    val chooseFile: () -> Unit = { runCatching { pickBundle.launch(BUNDLE_MIME_TYPES) } }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setNotificationsGranted(granted)
        if (!granted) notificationsDenied = true
    }
    val fixNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsDenied) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings(context)
        }
    }

    // Permissions and battery exemptions are granted in system screens: re-check on every return.
    LifecycleResumeEffect(Unit) {
        viewModel.setNotificationsGranted(NotificationManagerCompat.from(context).areNotificationsEnabled())
        viewModel.refreshBatteryState()
        onPauseOrDispose { }
    }

    val message = state.message?.resolve()
    LaunchedEffect(state.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.setup_remove_confirm_title)) },
            text = { Text(stringResource(R.string.setup_remove_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        viewModel.clearEnvironment()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.setup_remove_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val sourcesExpanded = sourcesExpandedChoice ?: (!state.installed && !state.canInstallFromEmbeddedOrUrl)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenLogs) {
                        Icon(Icons.Default.Terminal, contentDescription = stringResource(R.string.setup_open_log))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AdaptiveContent(modifier = Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "hero") { SetupHero() }

                if (state.mode == ConnectionMode.REMOTE) {
                    item(key = "remote") {
                        Banner(
                            icon = Icons.Default.Cloud,
                            title = stringResource(R.string.setup_remote_banner_title),
                            body = stringResource(R.string.setup_remote_banner_body),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                if (!state.installed && !state.canInstallFromEmbeddedOrUrl) {
                    item(key = "missing") {
                        if (state.brokenFullBuild) {
                            Banner(
                                icon = Icons.Default.WarningAmber,
                                title = stringResource(R.string.setup_broken_banner_title),
                                body = stringResource(R.string.setup_broken_banner_body),
                                container = MaterialTheme.colorScheme.errorContainer,
                                content = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            Banner(
                                icon = Icons.Default.Info,
                                title = stringResource(R.string.setup_lite_banner_title),
                                body = stringResource(R.string.setup_lite_banner_body),
                                container = MaterialTheme.colorScheme.tertiaryContainer,
                                content = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }

                item(key = "stepper") { SetupStepper(state) }

                item(key = "primary") {
                    PrimaryActionButton(
                        state = state,
                        onInstall = viewModel::install,
                        onChooseFile = chooseFile,
                        onStart = viewModel::startServer,
                        onUseLocal = { viewModel.setMode(ConnectionMode.LOCAL) },
                        onDone = onBack,
                    )
                }

                state.provision.error?.takeIf { state.provision.phase == ProvisionState.Phase.FAILED }?.let { error ->
                    item(key = "provision-error") {
                        ErrorCard(
                            message = error,
                            onRetry = if (state.canInstallFromEmbeddedOrUrl) viewModel::install else null,
                        )
                    }
                }

                val serverError = when {
                    !state.installed || state.mode != ConnectionMode.LOCAL -> null
                    state.server.phase == LocalServerState.Phase.FAILED -> state.server.message.orEmpty()
                    !state.server.running -> state.runtime.lastError
                    else -> null
                }
                if (serverError != null) {
                    item(key = "server-error") {
                        ErrorCard(
                            message = serverError.ifBlank { stringResource(R.string.setup_server_failed) },
                            hint = stringResource(R.string.setup_server_failed_hint),
                            onRetry = viewModel::startServer,
                        )
                    }
                }

                val needsNotifications = !state.notificationsGranted
                val needsBattery = !state.ignoringBatteryOptimizations
                if (needsNotifications || needsBattery) {
                    item(key = "background") {
                        SettingsGroup(title = stringResource(R.string.setup_background_title)) {
                            if (needsBattery) {
                                SettingsItem(
                                    title = stringResource(R.string.setup_battery_title),
                                    subtitle = stringResource(R.string.setup_battery_body),
                                    icon = Icons.Default.BatteryAlert,
                                    onClick = { openBatterySettings(context) },
                                    trailing = {
                                        FilledTonalButton(onClick = { openBatterySettings(context) }) {
                                            Text(stringResource(R.string.setup_action_allow))
                                        }
                                    },
                                )
                            }
                            if (needsBattery && needsNotifications) SettingsDivider()
                            if (needsNotifications) {
                                SettingsItem(
                                    title = stringResource(R.string.setup_notifications_title),
                                    subtitle = stringResource(R.string.setup_notifications_body),
                                    icon = Icons.Default.NotificationsOff,
                                    onClick = fixNotifications,
                                    trailing = {
                                        FilledTonalButton(onClick = fixNotifications) {
                                            Text(
                                                stringResource(
                                                    if (notificationsDenied || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                                        R.string.setup_action_open_settings
                                                    } else {
                                                        R.string.setup_action_allow
                                                    },
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                item(key = "sources") {
                    OtherSourcesCard(
                        state = state,
                        expanded = sourcesExpanded,
                        onToggle = { sourcesExpandedChoice = !sourcesExpanded },
                        onUrlChange = viewModel::onUrlChange,
                        onShaChange = viewModel::onShaChange,
                        onVersionChange = viewModel::onVersionChange,
                        onSave = viewModel::saveArtifactSource,
                        onReset = viewModel::resetArtifactSource,
                        onImport = chooseFile,
                    )
                }

                item(key = "logs") { LogPreviewCard(logs = state.logs, onOpenLogs = onOpenLogs) }

                if (state.installed || state.provision.phase == ProvisionState.Phase.FAILED) {
                    item(key = "maintenance") {
                        MaintenanceGroup(
                            state = state,
                            onReinstall = if (state.canInstallFromEmbeddedOrUrl) viewModel::install else chooseFile,
                            onRestart = viewModel::restartServer,
                            onStop = viewModel::stopServer,
                            onRemove = { confirmRemove = true },
                        )
                    }
                }

                item(key = "details") {
                    DetailsCard(
                        state = state,
                        expanded = detailsExpanded,
                        onToggle = { detailsExpanded = !detailsExpanded },
                        onRefresh = viewModel::refreshDiskUsage,
                    )
                }

                if (state.mode == ConnectionMode.LOCAL) {
                    item(key = "use-remote") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = { viewModel.setMode(ConnectionMode.REMOTE) }) {
                                Icon(Icons.Default.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.setup_action_use_remote))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- hero & banners

@Composable
private fun SetupHero() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(80.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.setup_hero_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.setup_hero_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Banner(
    icon: ImageVector,
    title: String,
    body: String,
    container: Color,
    content: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(modifier = Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ---------------------------------------------------------------------------------- stepper

@Composable
private fun SetupStepper(state: SetupUiState) {
    val formatSize = rememberSizeFormatter()
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            StepRow(
                number = 1,
                status = state.checkStep,
                title = stringResource(R.string.setup_step_check),
                subtitle = when {
                    state.installed && state.freeSpaceBytes > 0 ->
                        stringResource(R.string.setup_check_ready_free, formatSize(state.freeSpaceBytes))
                    state.installed -> stringResource(R.string.setup_check_ready)
                    state.brokenFullBuild && !state.usesCustomSource -> stringResource(R.string.setup_check_broken)
                    state.usesCustomSource -> stringResource(R.string.setup_check_download)
                    state.embeddedBundle -> stringResource(R.string.setup_check_embedded)
                    else -> stringResource(R.string.setup_check_lite)
                },
                isLast = false,
            )

            StepRow(
                number = 2,
                status = state.installStep,
                title = stringResource(R.string.setup_step_install),
                subtitle = when {
                    state.busy -> provisionPhaseLabel(state)
                    state.provision.phase == ProvisionState.Phase.FAILED -> stringResource(R.string.setup_install_failed)
                    state.installed && state.envSizeBytes > 0 -> stringResource(
                        R.string.setup_install_version_size,
                        state.installedVersion.orEmpty(),
                        formatSize(state.envSizeBytes),
                    )
                    state.installed -> stringResource(R.string.setup_install_version, state.installedVersion.orEmpty())
                    state.usesCustomSource && state.effectiveManifest.sizeBytes > 0 ->
                        stringResource(R.string.setup_install_download_size, formatSize(state.effectiveManifest.sizeBytes))
                    state.embeddedBundle && !state.usesCustomSource -> stringResource(R.string.setup_install_embedded)
                    else -> stringResource(R.string.setup_install_pending)
                },
                isLast = false,
            ) {
                if (state.busy) InstallProgress(state, formatSize)
            }

            val (serverLabel, _) = serverStatusLabel(state.server)
            StepRow(
                number = 3,
                status = if (state.mode == ConnectionMode.REMOTE && state.installed) StepStatus.PENDING else state.serverStep,
                title = stringResource(R.string.setup_step_start),
                subtitle = when {
                    !state.installed -> stringResource(R.string.setup_start_waiting)
                    state.mode == ConnectionMode.REMOTE -> stringResource(R.string.setup_start_remote)
                    else -> serverLabel
                },
                isLast = true,
            ) {
                if (state.installed && state.mode == ConnectionMode.LOCAL && state.server.restarts > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.setup_server_restarts,
                            state.server.restarts,
                            state.server.restarts,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    number: Int,
    status: StepStatus,
    title: String,
    subtitle: String?,
    isLast: Boolean,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val stateLabel = stringResource(
        when (status) {
            StepStatus.PENDING -> R.string.setup_step_state_pending
            StepStatus.ACTIVE -> R.string.setup_step_state_active
            StepStatus.DONE -> R.string.setup_step_state_done
            StepStatus.WARNING -> R.string.setup_step_state_warning
            StepStatus.ERROR -> R.string.setup_step_state_error
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) { stateDescription = stateLabel },
    ) {
        Column(
            modifier = Modifier.fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepIndicator(number = number, status = status)
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .background(
                            color = if (status == StepStatus.DONE) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 4.dp, bottom = if (isLast) 0.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (status == StepStatus.PENDING) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status == StepStatus.ERROR) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            extra()
        }
    }
}

@Composable
private fun StepIndicator(number: Int, status: StepStatus) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        StepStatus.DONE -> colors.primary to colors.onPrimary
        StepStatus.ACTIVE -> colors.primaryContainer to colors.onPrimaryContainer
        StepStatus.WARNING -> colors.tertiaryContainer to colors.onTertiaryContainer
        StepStatus.ERROR -> colors.errorContainer to colors.onErrorContainer
        StepStatus.PENDING -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = container, contentColor = content, modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) {
            when (status) {
                StepStatus.DONE -> Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                StepStatus.ACTIVE -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = content,
                )
                StepStatus.WARNING, StepStatus.ERROR ->
                    Icon(Icons.Default.PriorityHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                StepStatus.PENDING -> Text(number.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun InstallProgress(state: SetupUiState, formatSize: (Long) -> String) {
    val provision = state.provision
    // Unknown totals (a download without Content-Length, or the copy before provisioning starts)
    // get an indeterminate bar instead of one stuck at 0 %.
    val determinate = provision.busy &&
        !(provision.phase == ProvisionState.Phase.DOWNLOADING && provision.bytesTotal <= 0)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (determinate) {
            LinearProgressIndicator(progress = { provision.progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (provision.busy) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = if (provision.phase == ProvisionState.Phase.DOWNLOADING && provision.bytesDone > 0) {
                        if (provision.bytesTotal > 0) {
                            stringResource(
                                R.string.setup_progress_bytes,
                                formatSize(provision.bytesDone),
                                formatSize(provision.bytesTotal),
                            )
                        } else {
                            formatSize(provision.bytesDone)
                        }
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (determinate) {
                    Text(
                        text = stringResource(R.string.setup_progress_percent, state.progressPercent),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun provisionPhaseLabel(state: SetupUiState): String = stringResource(
    when {
        state.importing && !state.provision.busy -> R.string.setup_phase_importing
        else -> when (state.provision.phase) {
            ProvisionState.Phase.CHECKING -> R.string.setup_phase_checking
            ProvisionState.Phase.DOWNLOADING -> when {
                state.importing -> R.string.setup_phase_importing
                state.effectiveManifest.isDownload -> R.string.setup_phase_downloading
                else -> R.string.setup_phase_unpacking_builtin
            }
            ProvisionState.Phase.VERIFYING -> R.string.setup_phase_verifying
            ProvisionState.Phase.EXTRACTING -> R.string.setup_phase_extracting
            ProvisionState.Phase.INSTALLING -> R.string.setup_phase_installing
            else -> R.string.setup_phase_preparing
        }
    },
)

/**
 * Localised label and colour for the local server state. Shared with the log screen so both
 * describe the server the same way.
 */
@Composable
internal fun serverStatusLabel(server: LocalServerState): Pair<String, Color> = when (server.phase) {
    LocalServerState.Phase.STOPPED ->
        stringResource(R.string.setup_server_stopped) to MaterialTheme.colorScheme.outline
    LocalServerState.Phase.STARTING ->
        stringResource(R.string.setup_server_starting) to StatusColors.warn
    LocalServerState.Phase.READY -> if (server.healthy) {
        stringResource(R.string.setup_server_running, server.port) to StatusColors.ok
    } else {
        stringResource(R.string.setup_server_unhealthy) to StatusColors.warn
    }
    LocalServerState.Phase.STOPPING ->
        stringResource(R.string.setup_server_stopping) to StatusColors.warn
    LocalServerState.Phase.FAILED ->
        stringResource(R.string.setup_server_failed) to MaterialTheme.colorScheme.error
}

// ---------------------------------------------------------------------------------- primary action

@Composable
private fun PrimaryActionButton(
    state: SetupUiState,
    onInstall: () -> Unit,
    onChooseFile: () -> Unit,
    onStart: () -> Unit,
    onUseLocal: () -> Unit,
    onDone: () -> Unit,
) {
    val action = state.primaryAction
    val enabled = action != SetupPrimaryAction.INSTALLING && action != SetupPrimaryAction.WORKING
    val label = when (action) {
        SetupPrimaryAction.INSTALL -> stringResource(
            if (state.usesCustomSource) R.string.setup_action_download_install else R.string.setup_action_install,
        )
        SetupPrimaryAction.CHOOSE_FILE -> stringResource(R.string.setup_action_choose_file)
        SetupPrimaryAction.INSTALLING -> if (state.provision.busy) {
            stringResource(R.string.setup_action_installing_percent, state.progressPercent)
        } else {
            stringResource(R.string.setup_action_installing)
        }
        SetupPrimaryAction.START -> stringResource(R.string.setup_action_start)
        SetupPrimaryAction.WORKING -> stringResource(
            if (state.server.phase == LocalServerState.Phase.STOPPING) {
                R.string.setup_action_stopping
            } else {
                R.string.setup_action_starting
            },
        )
        SetupPrimaryAction.USE_LOCAL -> stringResource(R.string.setup_action_use_local)
        SetupPrimaryAction.DONE -> stringResource(R.string.setup_action_done)
    }
    val icon = when (action) {
        SetupPrimaryAction.INSTALL -> Icons.Default.Download
        SetupPrimaryAction.CHOOSE_FILE -> Icons.Default.UploadFile
        SetupPrimaryAction.START -> Icons.Default.PlayArrow
        SetupPrimaryAction.USE_LOCAL -> Icons.Default.Smartphone
        SetupPrimaryAction.DONE -> Icons.Default.Check
        SetupPrimaryAction.INSTALLING, SetupPrimaryAction.WORKING -> null
    }
    val onClick: () -> Unit = when (action) {
        SetupPrimaryAction.INSTALL -> onInstall
        SetupPrimaryAction.CHOOSE_FILE -> onChooseFile
        SetupPrimaryAction.START -> onStart
        SetupPrimaryAction.USE_LOCAL -> onUseLocal
        SetupPrimaryAction.DONE -> onDone
        SetupPrimaryAction.INSTALLING, SetupPrimaryAction.WORKING -> ({})
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 56.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

// ---------------------------------------------------------------------------------- other sources

@Composable
private fun OtherSourcesCard(
    state: SetupUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onUrlChange: (String) -> Unit,
    onShaChange: (String) -> Unit,
    onVersionChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    onImport: () -> Unit,
) {
    ExpandableCard(
        title = stringResource(R.string.setup_sources_title),
        subtitle = stringResource(
            if (state.embeddedBundle) R.string.setup_sources_subtitle_embedded else R.string.setup_sources_subtitle_lite,
        ),
        icon = Icons.Default.Tune,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        Text(
            text = stringResource(
                when {
                    state.usesCustomSource -> R.string.setup_sources_active_download
                    state.embeddedBundle -> R.string.setup_sources_active_builtin
                    else -> R.string.setup_sources_active_none
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(stringResource(R.string.setup_sources_link_title), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = state.draftUrl,
            onValueChange = onUrlChange,
            label = { Text(stringResource(R.string.setup_sources_url_label)) },
            placeholder = { Text("https://…") },
            singleLine = true,
            isError = !state.draftUrlValid,
            supportingText = if (!state.draftUrlValid) {
                { Text(stringResource(R.string.setup_sources_url_error)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        val shaMissing = state.draftUrl.isNotBlank() && state.draftSha.isBlank()
        OutlinedTextField(
            value = state.draftSha,
            onValueChange = onShaChange,
            label = { Text(stringResource(R.string.setup_sources_sha_label)) },
            singleLine = true,
            isError = !state.draftShaValid || shaMissing,
            supportingText = {
                Text(
                    stringResource(
                        when {
                            !state.draftShaValid -> R.string.setup_sources_sha_error
                            shaMissing -> R.string.setup_sources_sha_required
                            else -> R.string.setup_sources_sha_hint
                        },
                    ),
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.draftVersion,
            onValueChange = onVersionChange,
            label = { Text(stringResource(R.string.setup_sources_version_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = onSave, enabled = state.canSaveSource) {
                Text(stringResource(R.string.setup_sources_save))
            }
            if (state.embeddedBundle && state.usesCustomSource) {
                TextButton(onClick = onReset) { Text(stringResource(R.string.setup_sources_reset)) }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(stringResource(R.string.setup_sources_file_title), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.setup_sources_file_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onImport, enabled = !state.busy) {
            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_sources_import))
        }
    }
}

// ---------------------------------------------------------------------------------- log preview

@Composable
private fun LogPreviewCard(logs: List<String>, onOpenLogs: () -> Unit) {
    Surface(
        onClick = onOpenLogs,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Terminal)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = stringResource(R.string.setup_logs_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (logs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.setup_logs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        logs.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- maintenance

@Composable
private fun MaintenanceGroup(
    state: SetupUiState,
    onReinstall: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onRemove: () -> Unit,
) {
    val formatSize = rememberSizeFormatter()
    val local = state.mode == ConnectionMode.LOCAL
    SettingsGroup(title = stringResource(R.string.setup_manage_title)) {
        SettingsItem(
            title = stringResource(R.string.setup_manage_reinstall),
            subtitle = stringResource(
                if (state.canInstallFromEmbeddedOrUrl) {
                    R.string.setup_manage_reinstall_body
                } else {
                    R.string.setup_manage_reinstall_file_body
                },
            ),
            icon = Icons.Default.Download,
            enabled = !state.busy,
            onClick = onReinstall,
        )
        if (state.installed && local) {
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.setup_manage_restart),
                subtitle = stringResource(R.string.setup_manage_restart_body),
                icon = Icons.Default.RestartAlt,
                enabled = !state.busy,
                onClick = onRestart,
            )
        }
        if (state.server.running) {
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.setup_manage_stop),
                subtitle = stringResource(R.string.setup_manage_stop_body),
                icon = Icons.Default.StopCircle,
                onClick = onStop,
            )
        }
        SettingsDivider()
        SettingsItem(
            title = stringResource(R.string.setup_manage_remove),
            subtitle = if (state.envSizeBytes > 0) {
                stringResource(R.string.setup_manage_remove_body, formatSize(state.envSizeBytes))
            } else {
                stringResource(R.string.setup_manage_remove_body_unknown)
            },
            icon = Icons.Default.DeleteOutline,
            enabled = !state.busy,
            onClick = onRemove,
        )
    }
}

// ---------------------------------------------------------------------------------- details

@Composable
private fun DetailsCard(
    state: SetupUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    val formatSize = rememberSizeFormatter()
    ExpandableCard(
        title = stringResource(R.string.setup_details_title),
        subtitle = stringResource(R.string.setup_details_subtitle),
        icon = Icons.Default.Inventory2,
        expanded = expanded,
        onToggle = onToggle,
    ) {
        DetailRow(stringResource(R.string.setup_details_build), state.buildLabel.ifBlank { state.flavor })
        DetailRow(
            stringResource(R.string.setup_details_installed),
            state.installedVersion ?: stringResource(R.string.setup_details_not_installed),
        )
        DetailRow(
            stringResource(R.string.setup_details_bundle),
            stringResource(
                R.string.setup_details_bundle_value,
                state.effectiveManifest.version,
                formatSize(state.effectiveManifest.sizeBytes),
            ),
        )
        DetailRow(
            stringResource(R.string.setup_details_source),
            when {
                state.usesCustomSource ->
                    stringResource(R.string.setup_details_source_download, state.effectiveManifest.url)
                else -> when (state.bundleStatus) {
                    EmbeddedBundleStatus.EMBEDDED -> stringResource(R.string.setup_details_source_embedded)
                    EmbeddedBundleStatus.BROKEN_FULL -> stringResource(R.string.setup_details_source_broken)
                    EmbeddedBundleStatus.LITE -> stringResource(R.string.setup_details_source_lite)
                }
            },
        )
        DetailRow(stringResource(R.string.setup_details_on_disk), formatSize(state.envSizeBytes))
        DetailRow(stringResource(R.string.setup_details_free), formatSize(state.freeSpaceBytes))
        val (serverLabel, serverColor) = serverStatusLabel(state.server)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.setup_details_server),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.4f),
            )
            Row(Modifier.weight(0.6f), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = serverColor)
                Spacer(Modifier.width(8.dp))
                Text(serverLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.server.pid?.let { pid ->
            DetailRow("", stringResource(R.string.setup_details_process, pid, state.server.port))
        }
        if (state.envPath.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.setup_details_location),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        text = state.envPath,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        TextButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.setup_details_refresh))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.6f),
        )
    }
}

// ---------------------------------------------------------------------------------- building blocks

/** Rounded section whose body folds away; used for the secondary parts of the screen. */
@Composable
private fun ExpandableCard(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "chevron")
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(icon)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.setup_collapse else R.string.setup_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

/** Localised short file sizes ("105 MB", "105 Mo"); unknown sizes render as a dash. */
@Composable
private fun rememberSizeFormatter(): (Long) -> String {
    val context = LocalContext.current
    return remember(context) {
        { bytes: Long -> if (bytes <= 0L) "—" else Formatter.formatShortFileSize(context, bytes) }
    }
}

private fun openBatterySettings(context: Context) {
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:" + context.packageName))
    try {
        context.startActivity(request)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    } catch (_: SecurityException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}
