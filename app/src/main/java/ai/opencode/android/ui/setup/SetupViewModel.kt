package ai.opencode.android.ui.setup

import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalRuntimeState
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.server.provision.EnvConfig
import ai.opencode.android.server.provision.EnvManifest
import ai.opencode.android.server.provision.FileArtifactSource
import ai.opencode.android.server.provision.ProvisionResult
import ai.opencode.android.server.provision.ProvisionState
import ai.opencode.android.settings.ConnectionMode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

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
    /** `full` or `lite` (Gradle product flavor). */
    val flavor: String = "",
    /** Build stamp shown in Setup, e.g. `full-debug 1.1.3-full-debug`. */
    val buildLabel: String = "",
    /** True when the APK ships the bundle and Local mode can be installed without network. */
    val embeddedBundle: Boolean = false,
    /** Human readable bundle source label ("embedded in this APK (offline)" / Lite hint). */
    val embeddedBundleLabel: String = "",
    /** Full build whose asset is missing or unreadable: a broken artifact, not a Lite build. */
    val brokenFullBuild: Boolean = false,
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

    /** `full`: the environment ships inside the APK; `lite`: nothing embedded. */
    val isFullBuild: Boolean get() = embeddedBundle || flavor == "full"

    /** Can the Install button start without the user providing a URL or a file? */
    val canInstallFromEmbeddedOrUrl: Boolean get() = embeddedBundle || effectiveManifest.isDownload
}

/**
 * Drives the first-run experience: install the environment, start the server, handle retries and
 * expose diagnostics (paths, sizes, logs).
 */
class SetupViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        run {
            val embedded = container.embeddedBundleState
            SetupUiState(
                flavor = container.flavor,
                buildLabel = container.buildLabel,
                embeddedBundle = embedded.isEmbedded,
                embeddedBundleLabel = embedded.label,
                brokenFullBuild = embedded.isBrokenFull,
            )
        },
    )
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
            if (!container.hasEmbeddedBundle && !manifest.isDownload) {
                _state.update {
                    it.copy(message = "No environment in this APK — enter a bundle URL or import a .tar.gz file")
                }
                return@launch
            }
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

    /** Installs a bundle the user picked with the system file picker (works offline, no URL). */
    fun installFromFile(uri: Uri) {
        if (_state.value.provision.busy) return
        viewModelScope.launch {
            _state.update { it.copy(message = "Importing the selected bundle…") }
            val imported = runCatching { copyToPrivateStorage(uri) }.getOrNull()
            if (imported == null) {
                _state.update { it.copy(message = "Cannot read the selected file") }
                return@launch
            }
            val hash = runCatching { sha256(imported) }.getOrNull()
            if (hash == null) {
                _state.update { it.copy(message = "Cannot compute the SHA-256 of the selected file") }
                return@launch
            }
            val manifest = EnvManifest(
                version = "imported",
                asset = "",
                sha256 = hash,
                sizeBytes = imported.length(),
            )
            when (val result = container.provisioner.provision(manifest, FileArtifactSource(imported))) {
                is ProvisionResult.Ok -> {
                    _state.update { it.copy(message = "Environment ${result.version} installed") }
                    container.restartLocalServerService()
                }

                is ProvisionResult.Err -> _state.update { it.copy(message = result.message) }
            }
            refreshDiskUsage()
        }
    }

    private suspend fun copyToPrivateStorage(uri: Uri): File = withContext(Dispatchers.IO) {
        val target = File(container.envPaths.downloadDir, "imported-bundle.tar.gz")
        target.parentFile?.mkdirs()
        val input = container.context.contentResolver.openInputStream(uri)
            ?: error("The picked file cannot be opened")
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink, 64 * 1024) }
        }
        if (target.length() < 1_000_000L) error("The picked file is too small to be a bundle")
        target
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
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
