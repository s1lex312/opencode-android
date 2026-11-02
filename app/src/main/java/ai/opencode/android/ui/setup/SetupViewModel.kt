package ai.opencode.android.ui.setup

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalRuntimeState
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.server.provision.EmbeddedBundleStatus
import ai.opencode.android.server.provision.EnvConfig
import ai.opencode.android.server.provision.EnvManifest
import ai.opencode.android.server.provision.FileArtifactSource
import ai.opencode.android.server.provision.ProvisionResult
import ai.opencode.android.server.provision.ProvisionState
import ai.opencode.android.settings.ConnectionMode
import ai.opencode.android.ui.text.UiText
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Human readable byte size with English units. The Setup screen formats sizes with the platform
 * `Formatter` (localised); this stays for diagnostics and callers outside composition.
 */
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

/** Visual state of one step of the setup stepper. */
enum class StepStatus { PENDING, ACTIVE, DONE, WARNING, ERROR }

/** The single main call to action of the Setup screen, derived from the current state. */
enum class SetupPrimaryAction {
    /** Install from the embedded bundle or the configured URL. */
    INSTALL,

    /** Lite build without a URL: the only way forward is importing a bundle file. */
    CHOOSE_FILE,

    /** Installation is running; the button shows progress and is disabled. */
    INSTALLING,

    /** The environment is installed but the server is not running. */
    START,

    /** The server is booting or shutting down; disabled. */
    WORKING,

    /** Installed, but the app is in Remote mode: switch to this phone. */
    USE_LOCAL,

    /** Everything is up: leave the screen. */
    DONE,
}

data class SetupUiState(
    val loading: Boolean = true,
    /** `full` or `lite` (Gradle product flavor). */
    val flavor: String = "",
    /** Build stamp shown in Setup, e.g. `full-debug 1.1.3-full-debug`. */
    val buildLabel: String = "",
    /** Whether and how this APK carries the environment bundle. */
    val bundleStatus: EmbeddedBundleStatus = EmbeddedBundleStatus.LITE,
    val mode: ConnectionMode = ConnectionMode.LOCAL,
    val provision: ProvisionState = ProvisionState(),
    /** True while a user-picked file is being copied and installed (labels the progress). */
    val importing: Boolean = false,
    val installedVersion: String? = null,
    val manifest: EnvManifest = EnvManifest(),
    val effectiveManifest: EnvManifest = EnvManifest(),
    val envPath: String = "",
    val envSizeBytes: Long = 0,
    val freeSpaceBytes: Long = 0,
    val draftUrl: String = "",
    val draftSha: String = "",
    val draftVersion: String = "",
    val server: LocalServerState = LocalServerState(),
    val runtime: LocalRuntimeState = LocalRuntimeState(),
    val notificationsGranted: Boolean = true,
    val ignoringBatteryOptimizations: Boolean = true,
    val message: UiText? = null,
    val logs: List<String> = emptyList(),
) {
    /** True when the APK ships the bundle and Local mode can be installed without network. */
    val embeddedBundle: Boolean get() = bundleStatus == EmbeddedBundleStatus.EMBEDDED

    /** Full build whose asset is missing or unreadable: a broken artifact, not a Lite build. */
    val brokenFullBuild: Boolean get() = bundleStatus == EmbeddedBundleStatus.BROKEN_FULL

    /** Installing, or copying a picked file before the installation starts. */
    val busy: Boolean get() = provision.busy || importing
    val installed: Boolean get() = installedVersion != null
    val progressPercent: Int get() = (provision.progress * 100).toInt().coerceIn(0, 100)

    /** `full`: the environment ships inside the APK; `lite`: nothing embedded. */
    val isFullBuild: Boolean get() = embeddedBundle || flavor == "full"

    /** Can the Install button start without the user providing a URL or a file? */
    val canInstallFromEmbeddedOrUrl: Boolean get() = embeddedBundle || effectiveManifest.isDownload

    /** A user-provided download URL overrides the bundle inside the APK. */
    val usesCustomSource: Boolean get() = effectiveManifest.isDownload

    /** Downloads must be https:// (or a loopback file server), see `EnvConfig.source`. */
    val draftUrlValid: Boolean
        get() = draftUrl.isBlank() || draftUrl.trim().let { it.startsWith("https://") || it.startsWith("http://127.0.0.1") }

    /** Accepts the hash with or without `:`/space separators, in either case. */
    val draftShaValid: Boolean
        get() = draftSha.isBlank() || SHA_PATTERN.matches(draftSha.replace(":", "").replace(" ", ""))

    /** A URL without a hash would be refused by the provisioner, so it cannot be saved either. */
    val canSaveSource: Boolean
        get() = draftUrlValid && draftShaValid && (draftUrl.isBlank() || draftSha.isNotBlank())

    val checkStep: StepStatus
        get() = when {
            provision.phase == ProvisionState.Phase.CHECKING -> StepStatus.ACTIVE
            installed -> StepStatus.DONE
            brokenFullBuild && !effectiveManifest.isDownload -> StepStatus.ERROR
            canInstallFromEmbeddedOrUrl -> StepStatus.DONE
            else -> StepStatus.WARNING
        }

    val installStep: StepStatus
        get() = when {
            busy -> StepStatus.ACTIVE
            provision.phase == ProvisionState.Phase.FAILED -> StepStatus.ERROR
            installed -> StepStatus.DONE
            else -> StepStatus.PENDING
        }

    val serverStep: StepStatus
        get() = when {
            !installed -> StepStatus.PENDING
            server.phase == LocalServerState.Phase.READY -> if (server.healthy) StepStatus.DONE else StepStatus.WARNING
            server.phase == LocalServerState.Phase.STARTING ||
                server.phase == LocalServerState.Phase.STOPPING ||
                runtime.starting -> StepStatus.ACTIVE
            server.phase == LocalServerState.Phase.FAILED || runtime.lastError != null -> StepStatus.ERROR
            else -> StepStatus.PENDING
        }

    val primaryAction: SetupPrimaryAction
        get() = when {
            busy -> SetupPrimaryAction.INSTALLING
            !installed -> if (canInstallFromEmbeddedOrUrl) SetupPrimaryAction.INSTALL else SetupPrimaryAction.CHOOSE_FILE
            mode == ConnectionMode.REMOTE -> SetupPrimaryAction.USE_LOCAL
            server.phase == LocalServerState.Phase.READY -> SetupPrimaryAction.DONE
            server.phase == LocalServerState.Phase.STARTING ||
                server.phase == LocalServerState.Phase.STOPPING ||
                runtime.starting -> SetupPrimaryAction.WORKING
            else -> SetupPrimaryAction.START
        }

    private companion object {
        val SHA_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}

/** Raised when a picked file is obviously not an environment bundle. */
private class BundleTooSmallException : IOException("The picked file is too small to be a bundle")

/**
 * Drives the first-run experience: install the environment, start the server, handle retries and
 * expose diagnostics (paths, sizes, a short log preview).
 */
class SetupViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        SetupUiState(
            flavor = container.flavor,
            buildLabel = container.buildLabel,
            bundleStatus = container.embeddedBundleState.status,
        ),
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
                            draftVersion = settings.localServer.artifactVersion,
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

    /** Measures the installed environment off the main thread (walking the rootfs takes a while). */
    fun refreshDiskUsage() {
        viewModelScope.launch {
            val paths = container.envPaths
            val (size, free) = withContext(Dispatchers.IO) {
                val size = runCatching { paths.totalSizeBytes() }.getOrDefault(0L)
                val free = runCatching { paths.root.parentFile?.usableSpace ?: 0L }.getOrDefault(0L)
                size to free
            }
            _state.update { it.copy(envPath = paths.root.absolutePath, envSizeBytes = size, freeSpaceBytes = free) }
        }
    }

    fun install() {
        if (_state.value.busy) return
        viewModelScope.launch {
            val settings = runCatching { container.settingsRepository.current() }.getOrNull() ?: return@launch
            val manifest = EnvConfig.effective(assetManifest, settings.localServer)
            if (!container.hasEmbeddedBundle && !manifest.isDownload) {
                _state.update { it.copy(message = UiText.of(R.string.setup_msg_no_source)) }
                return@launch
            }
            when (val result = container.provisioner.provision(manifest)) {
                is ProvisionResult.Ok -> {
                    _state.update { it.copy(message = UiText.of(R.string.setup_msg_installed, result.version)) }
                    if (settings.isLocalMode) container.restartLocalServerService()
                }

                is ProvisionResult.Err -> _state.update { it.copy(message = UiText.raw(result.message)) }
            }
            refreshDiskUsage()
        }
    }

    /** Installs a bundle the user picked with the system file picker (works offline, no URL). */
    fun installFromFile(uri: Uri) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, message = UiText.of(R.string.setup_msg_importing)) }
            try {
                val imported = runCatching { copyToPrivateStorage(uri) }.getOrElse { error ->
                    val text = if (error is BundleTooSmallException) {
                        R.string.setup_msg_import_too_small
                    } else {
                        R.string.setup_msg_import_unreadable
                    }
                    _state.update { it.copy(message = UiText.of(text)) }
                    return@launch
                }
                val hash = runCatching { sha256(imported) }.getOrNull()
                if (hash == null) {
                    _state.update { it.copy(message = UiText.of(R.string.setup_msg_import_hash_failed)) }
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
                        _state.update { it.copy(message = UiText.of(R.string.setup_msg_installed, result.version)) }
                        container.restartLocalServerService()
                    }

                    is ProvisionResult.Err -> _state.update { it.copy(message = UiText.raw(result.message)) }
                }
                refreshDiskUsage()
            } finally {
                _state.update { it.copy(importing = false) }
            }
        }
    }

    private suspend fun copyToPrivateStorage(uri: Uri): File = withContext(Dispatchers.IO) {
        val target = File(container.envPaths.downloadDir, "imported-bundle.tar.gz")
        target.parentFile?.mkdirs()
        val input = container.context.contentResolver.openInputStream(uri)
            ?: throw IOException("The picked file cannot be opened")
        input.use { source ->
            target.outputStream().use { sink -> source.copyTo(sink, 64 * 1024) }
        }
        if (target.length() < MIN_BUNDLE_BYTES) {
            target.delete()
            throw BundleTooSmallException()
        }
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
                it.copy(
                    message = UiText.of(if (cleared) R.string.setup_msg_removed else R.string.setup_msg_remove_partial),
                )
            }
        }
    }

    fun startServer() {
        container.startLocalServerService()
        _state.update { it.copy(message = UiText.of(R.string.setup_msg_starting)) }
    }

    fun stopServer() {
        container.stopLocalServerService()
        _state.update { it.copy(message = UiText.of(R.string.setup_msg_stopped)) }
    }

    fun restartServer() {
        container.restartLocalServerService()
        _state.update { it.copy(message = UiText.of(R.string.setup_msg_restarting)) }
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
                it.copy(
                    message = UiText.of(
                        if (mode == ConnectionMode.LOCAL) R.string.setup_msg_mode_local else R.string.setup_msg_mode_remote,
                    ),
                )
            }
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(draftUrl = value) }

    fun onShaChange(value: String) = _state.update { it.copy(draftSha = value.trim()) }

    fun onVersionChange(value: String) = _state.update { it.copy(draftVersion = value) }

    fun saveArtifactSource() {
        val current = _state.value
        if (!current.canSaveSource) return
        viewModelScope.launch {
            container.settingsRepository.update { settings ->
                settings.copy(
                    localServer = settings.localServer.copy(
                        artifactUrl = current.draftUrl.trim(),
                        artifactSha256 = current.draftSha.trim(),
                        artifactVersion = current.draftVersion.trim(),
                    ),
                )
            }
            _state.update { it.copy(message = UiText.of(R.string.setup_msg_source_saved)) }
        }
    }

    /** Forgets the custom URL/hash/version so the bundle inside the APK is used again. */
    fun resetArtifactSource() {
        viewModelScope.launch {
            container.settingsRepository.update { settings ->
                settings.copy(
                    localServer = settings.localServer.copy(
                        artifactUrl = "",
                        artifactSha256 = "",
                        artifactVersion = "",
                    ),
                )
            }
            _state.update {
                it.copy(
                    draftUrl = "",
                    draftSha = "",
                    draftVersion = "",
                    message = UiText.of(R.string.setup_msg_source_reset),
                )
            }
        }
    }

    fun setNotificationsGranted(granted: Boolean) = _state.update { it.copy(notificationsGranted = granted) }

    fun refreshBatteryState() = _state.update { it.copy(ignoringBatteryOptimizations = container.isIgnoringBatteryOptimizations()) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    companion object {
        private const val LOG_PREVIEW_LINES = 5
        private const val MIN_BUNDLE_BYTES = 1_000_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SetupViewModel(container) }
        }
    }
}
