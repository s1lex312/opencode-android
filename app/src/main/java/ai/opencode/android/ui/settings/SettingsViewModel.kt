package ai.opencode.android.ui.settings

import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.di.AppContainer
import ai.opencode.android.security.NetworkPolicy
import ai.opencode.android.security.SecretStore
import ai.opencode.android.server.LocalServerManager
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.settings.AppSettings
import ai.opencode.android.settings.AuthMode
import ai.opencode.android.settings.SettingsRepository
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

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val draftServerUrl: String = AppSettings.DEFAULT_SERVER_URL,
    val draftUsername: String = "opencode",
    val draftAgent: String = "",
    val draftRootfs: String = "",
    val draftProot: String = "",
    val draftPort: String = "4096",
    val draftCommand: String = "",
    val urlError: String? = null,
    val message: String? = null,
    val passwordSet: Boolean = false,
    val tokenSet: Boolean = false,
    val secretsPersistent: Boolean = true,
    val models: List<ModelOption> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val modelsLoaded: Boolean = false,
    val testing: Boolean = false,
    val testMessage: String? = null,
    val testOk: Boolean = false,
    val connectionStatus: String = "",
    val localServer: LocalServerState = LocalServerState(),
) {
    val authMode: AuthMode get() = settings.authMode
    val permissionPolicy: PermissionPolicy get() = settings.permissionPolicy
    val selectedModel: ModelRef? get() = settings.model
    val allowLanCleartext: Boolean get() = settings.allowPrivateNetworkCleartext
    val autoConnect: Boolean get() = settings.autoConnect
    val localEnabled: Boolean get() = settings.localServer.enabled
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val connectionManager: ServerConnectionManager,
    private val repository: SessionRepository,
    private val localServerManager: LocalServerManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var draftsInitialised = false
    private var modelsRequested = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings -> applySettings(settings) }
        }
        viewModelScope.launch {
            connectionManager.state.collect { connection ->
                _state.update {
                    it.copy(
                        connectionStatus = when (connection) {
                            is ConnectionState.Idle -> "Not connected"
                            is ConnectionState.Connecting -> "Connecting…"
                            is ConnectionState.Connected -> "Connected to ${connection.baseUrl}"
                            is ConnectionState.Reconnecting -> "Reconnecting…"
                            is ConnectionState.Failed -> "Failed: ${connection.message}"
                        },
                    )
                }
                if (connection is ConnectionState.Connected && !modelsRequested) loadModels()
            }
        }
        viewModelScope.launch {
            localServerManager.state.collect { local -> _state.update { it.copy(localServer = local) } }
        }
        _state.update {
            it.copy(
                passwordSet = secretStore.hasPassword(),
                tokenSet = secretStore.hasToken(),
                secretsPersistent = secretStore.persistent,
            )
        }
    }

    // ------------------------------------------------------------------ connection

    fun onServerUrlChange(value: String) {
        _state.update { it.copy(draftServerUrl = value, urlError = null) }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(draftUsername = value) }
    }

    fun onAgentChange(value: String) {
        _state.update { it.copy(draftAgent = value) }
    }

    fun saveConnection() {
        val current = _state.value
        val policy = NetworkPolicy.normalize(current.draftServerUrl, current.settings.allowPrivateNetworkCleartext)
        if (policy is NetworkPolicy.Result.Rejected) {
            _state.update { it.copy(urlError = policy.reason) }
            return
        }
        val allowed = policy as NetworkPolicy.Result.Allowed
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(
                    serverUrl = allowed.url,
                    username = current.draftUsername.ifBlank { "opencode" },
                    agent = current.draftAgent.trim(),
                )
            }
            _state.update {
                it.copy(
                    urlError = null,
                    message = "Connection settings saved",
                    draftServerUrl = allowed.url,
                )
            }
            connectionManager.connect(force = true)
        }
    }

    fun setAuthMode(mode: AuthMode) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(authMode = mode) }
            _state.update { it.copy(message = "Authentication mode: ${mode.name.lowercase()}") }
        }
    }

    fun savePassword(value: String) {
        secretStore.setPassword(value)
        _state.update {
            it.copy(
                passwordSet = secretStore.hasPassword(),
                message = if (value.isBlank()) "Password cleared" else "Password stored in encrypted storage",
            )
        }
    }

    fun saveToken(value: String) {
        secretStore.setToken(value)
        _state.update {
            it.copy(
                tokenSet = secretStore.hasToken(),
                message = if (value.isBlank()) "Token cleared" else "Token stored in encrypted storage",
            )
        }
    }

    fun clearSecrets() {
        secretStore.clear()
        _state.update {
            it.copy(passwordSet = false, tokenSet = false, message = "Stored credentials cleared")
        }
    }

    fun setAllowLanCleartext(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(allowPrivateNetworkCleartext = enabled) }
            val draft = _state.value.draftServerUrl
            val policy = NetworkPolicy.normalize(draft, enabled)
            _state.update {
                it.copy(urlError = (policy as? NetworkPolicy.Result.Rejected)?.reason)
            }
        }
    }

    fun setAutoConnect(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.update { it.copy(autoConnect = enabled) } }
    }

    fun setPermissionPolicy(policy: PermissionPolicy) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(permissionPolicy = policy) }
            _state.update { it.copy(message = "Permission policy: ${policy.name.lowercase()}") }
        }
    }

    fun testConnection() {
        if (_state.value.testing) return
        _state.update { it.copy(testing = true, testMessage = null) }
        viewModelScope.launch {
            val current = _state.value
            val policy = NetworkPolicy.normalize(current.draftServerUrl, current.settings.allowPrivateNetworkCleartext)
            if (policy is NetworkPolicy.Result.Rejected) {
                _state.update { it.copy(testing = false, testOk = false, testMessage = policy.reason) }
                return@launch
            }
            val allowed = policy as NetworkPolicy.Result.Allowed
            val draftSettings = current.settings.copy(serverUrl = allowed.url)
            when (
                val result = connectionManager.testConnection(
                    settings = draftSettings,
                    password = secretStore.password(),
                    token = secretStore.token(),
                )
            ) {
                is ApiResult.Ok -> {
                    val version = result.value.version?.let { " v$it" } ?: ""
                    val flavor = if (result.value.flavor == ai.opencode.android.data.model.ApiFlavor.V2) "API v2" else "legacy API"
                    _state.update {
                        it.copy(
                            testing = false,
                            testOk = true,
                            testMessage = "Reachable: opencode$version ($flavor)",
                        )
                    }
                }

                is ApiResult.Err -> _state.update {
                    it.copy(testing = false, testOk = false, testMessage = result.message)
                }
            }
        }
    }

    // ------------------------------------------------------------------ models

    fun loadModels() {
        if (_state.value.modelsLoading) return
        modelsRequested = true
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            when (val result = repository.models()) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        modelsLoading = false,
                        modelsLoaded = true,
                        models = result.value,
                        modelsError = null,
                    )
                }

                is ApiResult.Err -> _state.update {
                    it.copy(modelsLoading = false, modelsLoaded = true, modelsError = result.message)
                }
            }
        }
    }

    fun selectModel(option: ModelOption) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(model = ModelRef(option.providerID, option.modelID))
            }
            _state.update { it.copy(message = "Model set to ${option.modelID}") }
        }
    }

    fun clearModel() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(model = null) }
            _state.update { it.copy(message = "Using the server default model") }
        }
    }

    // ------------------------------------------------------------------ local server

    fun onRootfsChange(value: String) = _state.update { it.copy(draftRootfs = value) }

    fun onProotChange(value: String) = _state.update { it.copy(draftProot = value) }

    fun onPortChange(value: String) = _state.update { it.copy(draftPort = value.filter { ch -> ch.isDigit() }.take(5)) }

    fun onCommandChange(value: String) = _state.update { it.copy(draftCommand = value) }

    fun saveLocalServerSettings() {
        val current = _state.value
        val port = current.draftPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 4096
        viewModelScope.launch {
            settingsRepository.update { settings ->
                settings.copy(
                    localServer = settings.localServer.copy(
                        rootfsPath = current.draftRootfs.trim(),
                        prootPath = current.draftProot.trim(),
                        port = port,
                        serverCommand = current.draftCommand.ifBlank {
                            "opencode serve --hostname 127.0.0.1 --port {port}"
                        },
                    ),
                )
            }
            _state.update { it.copy(draftPort = port.toString(), message = "Local server settings saved") }
        }
    }

    fun setLocalServerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(localServer = it.localServer.copy(enabled = enabled)) }
        }
    }

    /** Called by the UI after the notification permission (if needed) was granted. */
    fun startLocalServer() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(localServer = it.localServer.copy(enabled = true)) }
            localServerManager.start(settingsRepository.current().localServer)
        }
    }

    fun stopLocalServer() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(localServer = it.localServer.copy(enabled = false)) }
            localServerManager.stop()
        }
    }

    fun clearLocalServerLogs() = localServerManager.clearLogs()

    // ------------------------------------------------------------------ misc

    fun dismissMessage() = _state.update { it.copy(message = null, testMessage = null) }

    private fun applySettings(settings: AppSettings) {
        _state.update { current ->
            val updated = current.copy(
                settings = settings,
                loading = false,
                passwordSet = secretStore.hasPassword(),
                tokenSet = secretStore.hasToken(),
                secretsPersistent = secretStore.persistent,
            )
            if (!draftsInitialised) {
                draftsInitialised = true
                updated.copy(
                    draftServerUrl = settings.serverUrl,
                    draftUsername = settings.username,
                    draftAgent = settings.agent,
                    draftRootfs = settings.localServer.rootfsPath,
                    draftProot = settings.localServer.prootPath,
                    draftPort = settings.localServer.port.toString(),
                    draftCommand = settings.localServer.serverCommand,
                )
            } else {
                updated
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    secretStore = container.secretStore,
                    connectionManager = container.connectionManager,
                    repository = container.sessionRepository,
                    localServerManager = container.localServerManager,
                )
            }
        }
    }
}
