package ai.opencode.android.server

import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.security.LocalSecrets
import ai.opencode.android.security.SecretStore
import ai.opencode.android.server.provision.EnvConfig
import ai.opencode.android.server.provision.EnvProvisioner
import ai.opencode.android.server.provision.ProvisionResult
import ai.opencode.android.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class LocalRuntimeState(
    val starting: Boolean = false,
    val needsSetup: Boolean = false,
    val lastError: String? = null,
)

/**
 * Glue between provisioning, the process supervisor and the HTTP client for "everything on the
 * phone" mode.
 *
 * The flow is intentionally linear and idempotent:
 *
 * 1. generate/load the local server password (Keystore-backed storage);
 * 2. make sure the environment (proot + rootfs + opencode) is installed;
 * 3. start the server and wait for the health check;
 * 4. connect the app's HTTP client to `http://127.0.0.1:<port>`.
 */
class LocalRuntime(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val provisioner: EnvProvisioner,
    private val manager: LocalServerManager,
    private val connection: ServerConnectionManager,
    private val scope: CoroutineScope,
    /** True when the APK ships the bundle (Full flavor), so setup needs no network at all. */
    private val embeddedBundle: () -> Boolean = { false },
) {

    private val _state = MutableStateFlow(LocalRuntimeState())
    val state: StateFlow<LocalRuntimeState> = _state.asStateFlow()

    suspend fun ensurePassword(): String = LocalSecrets.ensurePassword(secretStore)

    suspend fun isInstalled(): Boolean = provisioner.isInstalled()

    /** Called by the foreground service and at app start (LOCAL mode only). */
    fun start() {
        scope.launch {
            val settings = runCatching { settingsRepository.current() }.getOrNull() ?: return@launch
            if (!settings.isLocalMode) {
                _state.value = LocalRuntimeState()
                return@launch
            }
            ensurePassword()
            if (!provisioner.isInstalled()) {
                // Full builds carry the environment in the APK: unpack it and continue, offline.
                // Lite builds must not start a silent 105 MB download, so they ask for a source.
                if (!embeddedBundle()) {
                    _state.value = LocalRuntimeState(needsSetup = true)
                    return@launch
                }
                val bundled = provisioner.bundled()
                if (bundled == null) {
                    _state.value = LocalRuntimeState(needsSetup = true, lastError = "No bundled environment")
                    return@launch
                }
                _state.value = LocalRuntimeState(starting = true)
                when (val installed = provisioner.provision(EnvConfig.effective(bundled, settings.localServer))) {
                    is ProvisionResult.Ok -> Unit
                    is ProvisionResult.Err -> {
                        _state.value = LocalRuntimeState(lastError = installed.message)
                        return@launch
                    }
                }
            }
            if (manager.state.value.phase == LocalServerState.Phase.READY) {
                connection.connect(force = true)
                return@launch
            }
            _state.value = LocalRuntimeState(starting = true)
            when (val result = manager.startAndAwaitReady(settings.localServer)) {
                is LocalReadyResult.Ready -> {
                    _state.value = LocalRuntimeState()
                    connection.connect(force = true)
                }

                is LocalReadyResult.NotInstalled -> _state.value = LocalRuntimeState(needsSetup = true)

                is LocalReadyResult.Failed -> _state.value = LocalRuntimeState(lastError = result.message)

                LocalReadyResult.Timeout -> _state.value =
                    LocalRuntimeState(lastError = "The local server did not answer in time")
            }
        }
    }

    fun stop() {
        manager.stop()
        _state.value = LocalRuntimeState()
    }

    /** Restarts the server after the environment was (re)installed. */
    fun restart() {
        scope.launch {
            manager.stop()
            delay(500)
            start()
        }
    }

    /**
     * Blocks the HTTP client until the local server is ready. Returns true when the connection may
     * proceed (also for REMOTE mode, where the local server is irrelevant).
     */
    suspend fun awaitReadyForClient(timeoutMs: Long = 180_000): Boolean {
        val settings = runCatching { settingsRepository.current() }.getOrNull() ?: return true
        if (!settings.isLocalMode) return true
        if (manager.state.value.phase == LocalServerState.Phase.READY) return true
        if (!provisioner.isInstalled()) return false

        if (manager.state.value.phase == LocalServerState.Phase.STARTING) {
            val state = withTimeoutOrNull(timeoutMs) {
                manager.state.first {
                    it.phase == LocalServerState.Phase.READY ||
                        it.phase == LocalServerState.Phase.FAILED ||
                        it.phase == LocalServerState.Phase.STOPPED
                }
            } ?: return false
            return state.phase == LocalServerState.Phase.READY
        }
        return manager.startAndAwaitReady(settings.localServer, timeoutMs) is LocalReadyResult.Ready
    }

}
