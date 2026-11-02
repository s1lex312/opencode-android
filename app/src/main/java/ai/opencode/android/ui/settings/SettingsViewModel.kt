package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.di.AppContainer
import ai.opencode.android.security.NetworkPolicy
import ai.opencode.android.security.SecretStore
import ai.opencode.android.server.LocalRuntime
import ai.opencode.android.server.LocalRuntimeState
import ai.opencode.android.server.LocalServerManager
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.server.provision.EnvProvisioner
import ai.opencode.android.settings.AppSettings
import ai.opencode.android.settings.AuthMode
import ai.opencode.android.settings.ConnectionMode
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.ui.text.UiText
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

/** Outcome of the "Test" button, shown inline under the remote server form. */
data class ConnectionTestResult(val ok: Boolean, val text: UiText)

/**
 * State behind the settings hub and the Connection screen.
 *
 * The password/token drafts live here (in memory only) rather than in `rememberSaveable`, so a
 * half-typed secret survives rotation without ever being written into the saved-instance Bundle.
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val connection: ConnectionState = ConnectionState.Idle,
    val localServer: LocalServerState = LocalServerState(),
    val runtime: LocalRuntimeState = LocalRuntimeState(),
    /** False until the installed environment has been looked up on disk. */
    val envChecked: Boolean = false,
    val envVersion: String? = null,
    val draftServerUrl: String = AppSettings.DEFAULT_SERVER_URL,
    val draftUsername: String = AppSettings.DEFAULT_USERNAME,
    val draftPassword: String = "",
    val draftToken: String = "",
    val urlError: UiText? = null,
    val passwordSet: Boolean = false,
    val tokenSet: Boolean = false,
    val secretsPersistent: Boolean = true,
    val testing: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    /** One-shot confirmation shown as a snackbar, then cleared with [SettingsViewModel.dismissMessage]. */
    val message: UiText? = null,
) {
    val authMode: AuthMode get() = settings.authMode
    val connectionMode: ConnectionMode get() = settings.connectionMode
    val isLocalMode: Boolean get() = settings.isLocalMode
    val allowLanCleartext: Boolean get() = settings.allowPrivateNetworkCleartext
    val envInstalled: Boolean get() = envVersion != null
    val hasStoredSecrets: Boolean get() = passwordSet || tokenSet

    override fun toString(): String =
        "SettingsUiState(mode=$connectionMode, connection=$connection, url=$draftServerUrl, testing=$testing)"
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val connectionManager: ServerConnectionManager,
    private val localServerManager: LocalServerManager,
    private val localRuntime: LocalRuntime,
    private val provisioner: EnvProvisioner,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private var draftsInitialised = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings -> applySettings(settings) }
        }
        viewModelScope.launch {
            connectionManager.state.collect { connection -> _state.update { it.copy(connection = connection) } }
        }
        viewModelScope.launch {
            localServerManager.state.collect { local -> _state.update { it.copy(localServer = local) } }
        }
        viewModelScope.launch {
            localRuntime.state.collect { runtime -> _state.update { it.copy(runtime = runtime) } }
        }
        viewModelScope.launch {
            provisioner.state.collect {
                // installedVersion() validates files on disk; keep that off the main thread.
                val version = withContext(Dispatchers.IO) { provisioner.installedVersion() }
                _state.update { it.copy(envChecked = true, envVersion = version) }
            }
        }
        refreshSecretFlags()
    }

    // ------------------------------------------------------------------ mode & local server

    fun setConnectionMode(mode: ConnectionMode) {
        if (_state.value.connectionMode == mode && !_state.value.loading) return
        viewModelScope.launch {
            settingsRepository.update { it.copy(connectionMode = mode) }
            if (mode == ConnectionMode.LOCAL) {
                localRuntime.start()
                _state.update { it.copy(message = UiText.of(R.string.settings_msg_mode_local)) }
            } else {
                localRuntime.stop()
                connectionManager.connect(force = true)
                _state.update { it.copy(message = UiText.of(R.string.settings_msg_mode_remote)) }
            }
        }
    }

    /** Called by the UI after the notification permission (if needed) was requested. */
    fun startLocalServer() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(localServer = it.localServer.copy(enabled = true)) }
            localRuntime.start()
        }
    }

    fun stopLocalServer() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(localServer = it.localServer.copy(enabled = false)) }
            localRuntime.stop()
        }
    }

    // ------------------------------------------------------------------ remote server form

    fun onServerUrlChange(value: String) {
        _state.update { it.copy(draftServerUrl = value, urlError = null, testResult = null) }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(draftUsername = value, testResult = null) }
    }

    fun onPasswordChange(value: String) {
        _state.update { it.copy(draftPassword = value, testResult = null) }
    }

    fun onTokenChange(value: String) {
        _state.update { it.copy(draftToken = value, testResult = null) }
    }

    fun setAuthMode(mode: AuthMode) {
        _state.update { it.copy(testResult = null) }
        viewModelScope.launch { settingsRepository.update { it.copy(authMode = mode) } }
    }

    fun setAllowLanCleartext(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(allowPrivateNetworkCleartext = enabled) }
            val draft = _state.value.draftServerUrl
            if (draft.isBlank()) return@launch
            val rejected = NetworkPolicy.normalize(draft, enabled) as? NetworkPolicy.Result.Rejected
            _state.update { it.copy(urlError = rejected?.let { reason -> UiText.raw(reason.reason) }) }
        }
    }

    /**
     * Validates the address, stores the typed secret for the selected auth mode, persists the form
     * and reconnects. Returns false (and flags the field) when the address is rejected.
     */
    fun saveConnection(): Boolean {
        val current = _state.value
        val allowed = validatedUrl(current) ?: return false
        when (current.authMode) {
            AuthMode.BASIC -> if (current.draftPassword.isNotEmpty()) secretStore.setPassword(current.draftPassword)
            AuthMode.BEARER -> if (current.draftToken.isNotEmpty()) secretStore.setToken(current.draftToken)
            AuthMode.NONE -> Unit
        }
        _state.update {
            it.copy(
                draftServerUrl = allowed.url,
                draftPassword = "",
                draftToken = "",
                urlError = null,
                passwordSet = secretStore.hasPassword(),
                tokenSet = secretStore.hasToken(),
                message = UiText.of(R.string.settings_msg_saved, allowed.host),
            )
        }
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(
                    serverUrl = allowed.url,
                    username = current.draftUsername.trim().ifBlank { AppSettings.DEFAULT_USERNAME },
                )
            }
            connectionManager.connect(force = true)
        }
        return true
    }

    /** One-shot health check of the form as typed, without touching the live connection. */
    fun testConnection() {
        val current = _state.value
        if (current.testing) return
        val allowed = validatedUrl(current) ?: return
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val draftSettings = current.settings.copy(
                connectionMode = ConnectionMode.REMOTE,
                serverUrl = allowed.url,
                username = current.draftUsername.trim().ifBlank { AppSettings.DEFAULT_USERNAME },
            )
            val result = connectionManager.testConnection(
                settings = draftSettings,
                password = current.draftPassword.ifEmpty { secretStore.password() },
                token = current.draftToken.ifEmpty { secretStore.token() },
            )
            val outcome = when (result) {
                is ApiResult.Ok -> {
                    val api = UiText.of(
                        if (result.value.flavor == ApiFlavor.V2) R.string.settings_api_v2 else R.string.settings_api_legacy,
                    )
                    val version = result.value.version
                    ConnectionTestResult(
                        ok = true,
                        text = if (version.isNullOrBlank()) {
                            UiText.of(R.string.settings_test_ok_unknown_version, api)
                        } else {
                            UiText.of(R.string.settings_test_ok, version, api)
                        },
                    )
                }

                is ApiResult.Err -> ConnectionTestResult(ok = false, text = UiText.raw(result.message))
            }
            _state.update { it.copy(testing = false, testResult = outcome) }
        }
    }

    /** Forgets the remote server password and token; the built-in server's own password is kept. */
    fun clearCredentials() {
        secretStore.setPassword(null)
        secretStore.setToken(null)
        _state.update {
            it.copy(
                draftPassword = "",
                draftToken = "",
                passwordSet = secretStore.hasPassword(),
                tokenSet = secretStore.hasToken(),
                message = UiText.of(R.string.settings_msg_credentials_cleared),
            )
        }
    }

    // ------------------------------------------------------------------ misc

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun validatedUrl(current: SettingsUiState): NetworkPolicy.Result.Allowed? {
        if (current.draftServerUrl.isBlank()) {
            _state.update { it.copy(urlError = UiText.of(R.string.settings_remote_url_empty)) }
            return null
        }
        return when (val policy = NetworkPolicy.normalize(current.draftServerUrl, current.allowLanCleartext)) {
            is NetworkPolicy.Result.Allowed -> policy
            is NetworkPolicy.Result.Rejected -> {
                _state.update { it.copy(urlError = UiText.raw(policy.reason)) }
                null
            }
        }
    }

    private fun refreshSecretFlags() {
        _state.update {
            it.copy(
                passwordSet = secretStore.hasPassword(),
                tokenSet = secretStore.hasToken(),
                secretsPersistent = secretStore.persistent,
            )
        }
    }

    private fun applySettings(settings: AppSettings) {
        _state.update { current ->
            val updated = current.copy(settings = settings, loading = false)
            if (!draftsInitialised) {
                draftsInitialised = true
                updated.copy(
                    draftServerUrl = settings.serverUrl,
                    draftUsername = settings.username,
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
                    localServerManager = container.localServerManager,
                    localRuntime = container.localRuntime,
                    provisioner = container.provisioner,
                )
            }
        }
    }
}
