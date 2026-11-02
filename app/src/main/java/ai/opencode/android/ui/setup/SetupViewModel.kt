package ai.opencode.android.ui.setup

import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalRuntimeState
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.server.provision.EnvConfig
import ai.opencode.android.server.provision.EnvManifest
import ai.opencode.android.server.provision.ProvisionResult
import ai.opencode.android.server.provision.ProvisionState
import ai.opencode.android.settings.ConnectionMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Human readable byte size used by the setup screen. */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit += 1
    }
    return if (unit == 0) "$bytes ${units[0]}" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}

data class SetupUiState(
    val loading: Boolean = true,
    val mode: ConnectionMode = ConnectionMode.LOCAL,
    val provision: ProvisionState = ProvisionState(),
    val installedVersion: String? = null,
    val manifest: EnvManifest = EnvManifest(),
    val effectiveManifest: EnvManifest = EnvManifest(),
    val envPath: String = "",
    val envSizeBytes: Long = 0,
    val freeSpaceBytes: Long = 0,
    val draftUrl: String = "",
    val draftSha: String = "",
    val server: LocalServerState = LocalServerState(),
    val runtime: LocalRuntimeState = LocalRuntimeState(),
    val passwordSet: Boolean = false,
    val notificationsGranted: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = false,
    val message: String? = null,
    val logs: List<String> = emptyList(),
) {
    val busy: Boolean get() = provision.busy
    val installed: Boolean get() = installedVersion != null
    val progressPercent: Int get() = (provision.progress * 100).toInt().coerceIn(0, 100)
    val sizeLabel: String get() = formatBytes(envSizeBytes)
    val freeSpaceLabel: String get() = formatBytes(freeSpaceBytes)
    val bundleSizeLabel: String get() = formatBytes(effectiveManifest.sizeBytes)
}

/**
 * Drives the first-run experience: install the environment, start the server, handle retries and
 * expose diagnostics (paths, sizes, logs).
 */
class SetupViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private var draftsInitialised = false
    private val assetManifest: EnvManifest =
        container.provisioner.bundled() ?: EnvManifest(version = "unset")

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                val effective = EnvConfig.effective(assetManifest, settings.localServer)
                _state.update { current ->
                    val base = current.copy(
                        loading = false,
                        mode = settings.connectionMode,
                        manifest = assetManifest,
                        effectiveManifest = effective,
                        installedVersion = container.provisioner.installedVersion(),
                        ignoringBatteryOptimizations = container.isIgnoringBatteryOptimizations(),
                    )
                    if (draftsInitialised) {
                        base
                    } else {
                        draftsInitialised = true
                        base.copy(
                            draftUrl = settings.localServer.artifactUrl,
                            draftSha = settings.localServer.artifactSha256,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            container.provisioner.state.collect { provision ->
                _state.update {
                    it.copy(
                        provision = provision,
                        installedVersion = container.provisioner.installedVersion(),
                        passwordSet = container.secretStore.hasLocalPassword(),
                    )
                }
            }
        }
        viewModelScope.launch {
            container.localServerManager.state.collect { server ->
                _state.update { it.copy(server = server, logs = server.logs.takeLast(LOG_PREVIEW_LINES)) }
            }
        }
        viewModelScope.launch {
            container.localRuntime.state.collect { runtime -> _state.update { it.copy(runtime = runtime) } }
        }
        refreshDiskUsage()
    }

    fun refreshDiskUsage() {
        val paths = container.envPaths
        val size = runCatching { paths.totalSizeBytes() }.getOrDefault(0L)
        val free = runCatching { paths.root.parentFile?.usableSpace ?: 0L }.getOrDefault(0L)
        _state.update { it.copy(envPath = paths.root.absolutePath, envSizeBytes = size, freeSpaceBytes = free) }
    }

    fun install() {
        if (_state.value.provision.busy) return
        viewModelScope.launch {
            val settings = runCatching { container.settingsRepository.current() }.getOrNull() ?: return@launch
            val manifest = EnvConfig.effective(assetManifest, settings.localServer)
            val result = container.provisioner.provision(manifest)
            when (result) {
                is ProvisionResult.Ok -> {
                    _state.update { it.copy(message = "Environment ${result.version} installed") }
                    refreshDiskUsage()
                    if (settings.isLocalMode) container.restartLocalServerService()
                }

                is ProvisionResult.Err -> _state.update { it.copy(message = result.message) }
            }
            refreshDiskUsage()
        }
    }

    fun clearEnvironment() {
        viewModelScope.launch {
            container.stopLocalServerService()
            val cleared = container.provisioner.clear()
            refreshDiskUsage()
            _state.update {
                it.copy(message = if (cleared) "Environment removed" else "Some files could not be removed")
            }
        }
    }

    fun startServer() {
        container.startLocalServerService()
        _state.update { it.copy(message = "Starting the local server…") }
    }

    fun stopServer() {
        container.stopLocalServerService()
        _state.update { it.copy(message = "Local server stopped") }
    }

    fun restartServer() {
        container.restartLocalServerService()
        _state.update { it.copy(message = "Restarting the local server…") }
    }

    fun setMode(mode: ConnectionMode) {
        viewModelScope.launch {
            container.settingsRepository.update { it.copy(connectionMode = mode) }
            if (mode == ConnectionMode.LOCAL) {
                container.startLocalServerService()
            } else {
                container.stopLocalServerService()
                container.connectionManager.connect(force = true)
            }
            _state.update {
                it.copy(message = if (mode == ConnectionMode.LOCAL) "Local mode: everything runs on this phone" else "Remote mode: using the configured server")
            }
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(draftUrl = value) }

    fun onShaChange(value: String) = _state.update { it.copy(draftSha = value.trim()) }

    fun saveArtifactSource() {
        val current = _state.value
        viewModelScope.launch {
            container.settingsRepository.update { settings ->
                settings.copy(
                    localServer = settings.localServer.copy(
                        artifactUrl = current.draftUrl.trim(),
                        artifactSha256 = current.draftSha.trim(),
                    ),
                )
            }
            _state.update { it.copy(message = "Artifact source saved") }
        }
    }

    fun clearLogs() {
        container.localServerManager.clearLogs()
        _state.update { it.copy(logs = emptyList()) }
    }

    fun refreshLogs() {
        _state.update { it.copy(logs = container.localServerManager.logLines(200)) }
    }

    fun setNotificationsGranted(granted: Boolean) = _state.update { it.copy(notificationsGranted = granted) }

    fun refreshBatteryState() = _state.update { it.copy(ignoringBatteryOptimizations = container.isIgnoringBatteryOptimizations()) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        private const val LOG_PREVIEW_LINES = 40

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SetupViewModel(container) }
        }

    }
}
