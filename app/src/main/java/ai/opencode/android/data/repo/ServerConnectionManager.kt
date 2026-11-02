package ai.opencode.android.data.repo

import ai.opencode.android.data.events.EventMapper
import ai.opencode.android.data.events.OpencodeEvent
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.ServerInfo
import ai.opencode.android.data.remote.ApiFactory
import ai.opencode.android.data.remote.ConnectedServer
import ai.opencode.android.data.remote.OpencodeApi
import ai.opencode.android.data.remote.NetworkErrors
import ai.opencode.android.data.remote.StreamSignal
import ai.opencode.android.data.remote.parseJsonObjectOrNull
import ai.opencode.android.security.SecretStore
import ai.opencode.android.settings.AppSettings
import ai.opencode.android.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the one and only connection to the opencode server: HTTP client, detected API flavour,
 * the SSE subscription and the connection state exposed to the UI.
 *
 * Screens never create their own sockets; they read [state] and [events]. That keeps a single
 * event stream alive across navigation, which is both faster and friendlier to the server.
 */
class ServerConnectionManager(
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<OpencodeEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<OpencodeEvent> = _events.asSharedFlow()

    @Volatile
    private var connected: ConnectedServer? = null

    private var connectionJob: Job? = null

    val api: OpencodeApi? get() = connected?.api

    val serverKey: String
        get() = connected?.api?.baseUrl
            ?: settingsSnapshot?.effectiveServerUrl
            ?: AppSettings.DEFAULT_SERVER_URL

    private var settingsSnapshot: AppSettings? = null

    /**
     * Gate that blocks connecting until the in-app server answers its health check. Installed by
     * [ai.opencode.android.di.AppContainer] when LOCAL mode is in use; defaults to "always ready"
     * so isolated tests and REMOTE mode are unaffected.
     */
    var localReadiness: suspend () -> Boolean = { true }

    val isConnected: Boolean get() = _state.value is ConnectionState.Connected

    /** Connects (or reconnects) using the persisted settings. Safe to call repeatedly. */
    fun connect(force: Boolean = false) {
        if (connectionJob?.isActive == true && !force) return
        connectionJob?.cancel()
        connectionJob = scope.launch {
            _state.value = ConnectionState.Connecting
            val settings = try {
                settingsRepository.current()
            } catch (t: Throwable) {
                _state.value = ConnectionState.Failed("Cannot read settings: ${t.javaClass.simpleName}")
                return@launch
            }
            settingsSnapshot = settings
            val effective = if (settings.isLocalMode) {
                settings.copy(
                    serverUrl = settings.localServer.url,
                    authMode = settings.effectiveAuthMode,
                    username = settings.effectiveUsername,
                )
            } else {
                settings
            }
            try {
                if (settings.isLocalMode && !localReadiness()) {
                    _state.value = ConnectionState.Failed(
                        message = "The local server is not ready",
                        hint = "Open Setup to install the environment, or start the server in Settings.",
                    )
                    return@launch
                }
                val result = ApiFactory(
                    settings = effective,
                    password = if (settings.isLocalMode) secretStore.localPassword() else secretStore.password(),
                    token = if (settings.isLocalMode) null else secretStore.token(),
                ).connect()
                connected = result
                _state.value = ConnectionState.Connected(result.info, result.api.baseUrl)
                observe(result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                connected = null
                _state.value = ConnectionState.Failed(NetworkErrors.describe(t), hintFor(t))
            }
        }
    }

    fun reconnect() = connect(force = true)

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        connected = null
        _state.value = ConnectionState.Idle
    }

    /** One-shot health check used from Settings without disturbing the live connection. */
    suspend fun testConnection(settings: AppSettings, password: String?, token: String?): ApiResult<ServerInfo> =
        runApiCall {
            ApiFactory(settings, password, token).connect().info
        }

    private suspend fun observe(connection: ConnectedServer) {
        var attempt = 0
        var openedBefore = false
        connection.api.eventStream().collect { signal ->
            when (signal) {
                is StreamSignal.Opened -> {
                    val wasReconnect = openedBefore || attempt > 0
                    attempt = 0
                    openedBefore = true
                    _state.value = ConnectionState.Connected(connection.info, connection.api.baseUrl)
                    if (wasReconnect) {
                        // Events emitted while the stream was down are gone for good: ask the
                        // ViewModels to re-read the server projection instead of trusting stale UI.
                        _events.tryEmit(OpencodeEvent.SessionsChanged)
                        _events.tryEmit(OpencodeEvent.MessagesInvalidated(null))
                    }
                }

                is StreamSignal.Frame -> {
                    val json = parseJsonObjectOrNull(signal.data) ?: return@collect
                    val event = EventMapper.map(connection.api.flavor, json)
                    if (event !is OpencodeEvent.Ignored) _events.tryEmit(event)
                }

                is StreamSignal.Failure -> {
                    attempt += 1
                    _state.value = if (signal.fatal) {
                        ConnectionState.Failed(
                            signal.message,
                            hint = if (signal.authFailure) "Check the password or token in Settings." else null,
                        )
                    } else {
                        ConnectionState.Reconnecting(attempt, signal.message)
                    }
                }
            }
        }
    }

    private fun hintFor(t: Throwable): String? = when (t) {
        is java.net.ConnectException -> "Start the server with `opencode serve --hostname 0.0.0.0 --port 4096`."
        is java.net.UnknownHostException -> "Check the address and your network connection."
        else -> null
    }
}
