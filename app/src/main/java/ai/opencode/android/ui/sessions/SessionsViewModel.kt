package ai.opencode.android.ui.sessions

import ai.opencode.android.data.events.OpencodeEvent
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.settings.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ai.opencode.android.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<ServerSession> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val creating: Boolean = false,
    val error: String? = null,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val connectionStatus: String = "Not connected",
    val connectionHint: String? = null,
    val serverLabel: String = "",
    val serverFlavor: String? = null,
    val fromCache: Boolean = false,
    val pendingOpenSessionId: String? = null,
    val connection: ConnectionState = ConnectionState.Idle,
)

class SessionsViewModel(
    private val repository: SessionRepository,
    private val connection: ServerConnectionManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionsUiState())
    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var lastLoadAt = 0L

    init {
        collectConnection()
        collectEvents()
        viewModelScope.launch {
            val cached = repository.cachedSessions()
            if (cached.isNotEmpty()) {
                _state.update { current ->
                    if (current.sessions.isEmpty()) {
                        current.copy(sessions = cached.sortedByDescending { it.updated }, fromCache = true)
                    } else {
                        current
                    }
                }
            }
        }
        viewModelScope.launch {
            val settings = runCatching { settingsRepository.current() }.getOrNull() ?: return@launch
            _state.update { it.copy(serverLabel = settings.serverUrl) }
            if (settings.autoConnect) {
                connection.connect()
            } else if (!connection.isConnected) {
                // Nothing is going to load: show the empty "Not connected" state, not a spinner.
                _state.update { it.copy(loading = false) }
            }
        }
    }

    fun refresh() = load(showSpinner = true)

    fun connect() = connection.connect(force = true)

    fun createSession() {
        if (_state.value.creating) return
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.createSession()) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(creating = false, pendingOpenSessionId = result.value.id) }
                    load(showSpinner = false)
                }

                is ApiResult.Err -> _state.update { it.copy(creating = false, error = result.message) }
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            when (val result = repository.deleteSession(sessionId)) {
                is ApiResult.Ok -> {
                    _state.update { current -> current.copy(sessions = current.sessions.filterNot { it.id == sessionId }) }
                }

                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun consumeOpenSession() {
        _state.update { it.copy(pendingOpenSessionId = null) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private fun load(showSpinner: Boolean) {
        loadJob?.cancel()
        if (showSpinner) _state.update { it.copy(loading = it.sessions.isEmpty(), refreshing = it.sessions.isNotEmpty()) }
        loadJob = viewModelScope.launch {
            val result = repository.loadSessions()
            lastLoadAt = System.currentTimeMillis()
            when (result) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        sessions = result.value.sortedByDescending { session -> session.updated },
                        loading = false,
                        refreshing = false,
                        fromCache = false,
                        error = null,
                    )
                }

                is ApiResult.Err -> _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = if (it.sessions.isEmpty()) result.message else it.error,
                        connectionHint = result.message.takeIf { _ -> !result.notConnected },
                    )
                }
            }
        }
    }

    private fun collectConnection() {
        viewModelScope.launch {
            connection.state.collect { state ->
                _state.update {
                    it.copy(
                        connection = state,
                        connected = state is ConnectionState.Connected,
                        connecting = state is ConnectionState.Connecting || state is ConnectionState.Reconnecting,
                        connectionStatus = state.describe(),
                        connectionHint = (state as? ConnectionState.Failed)?.hint,
                        serverFlavor = (state as? ConnectionState.Connected)?.info?.flavor?.let { flavor ->
                            if (flavor == ai.opencode.android.data.model.ApiFlavor.V2) "API v2" else "legacy API"
                        },
                        serverLabel = (state as? ConnectionState.Connected)?.baseUrl ?: it.serverLabel,
                        error = (state as? ConnectionState.Failed)?.message ?: it.error,
                    )
                }
                when (state) {
                    is ConnectionState.Connected -> load(showSpinner = _state.value.sessions.isEmpty())
                    is ConnectionState.Idle, is ConnectionState.Failed ->
                        _state.update { it.copy(loading = false, refreshing = false) }
                    else -> Unit
                }
            }
        }
    }

    private fun collectEvents() {
        viewModelScope.launch {
            connection.events.collect { event ->
                if (event is OpencodeEvent.SessionsChanged) {
                    // Coalesce bursts of session events into a single refresh.
                    val elapsed = System.currentTimeMillis() - lastLoadAt
                    if (elapsed < 1_000) delay(1_000 - elapsed)
                    load(showSpinner = false)
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SessionsViewModel(
                    repository = container.sessionRepository,
                    connection = container.connectionManager,
                    settingsRepository = container.settingsRepository,
                )
            }
        }
    }
}

private fun ConnectionState.describe(): String = when (this) {
    is ConnectionState.Idle -> "Not connected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Connected -> "Connected"
    is ConnectionState.Reconnecting -> "Reconnecting (attempt $attempt)…"
    is ConnectionState.Failed -> "Connection failed"
}
