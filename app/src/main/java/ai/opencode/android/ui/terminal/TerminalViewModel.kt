package ai.opencode.android.ui.terminal

import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.di.AppContainer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

data class TerminalUiState(
    val status: Status = Status.CONNECTING,
    val error: String? = null,
    /** Changes whenever the buffer does; the screen reads the buffer itself. */
    val version: Long = 0,
) {
    enum class Status { CONNECTING, OPEN, CLOSED, FAILED }
}

/**
 * A shell on the opencode server (`POST /pty` + WebSocket `/pty/{id}/connect`). Works for both the
 * in-app server (a shell inside the proot rootfs) and remote servers.
 */
class TerminalViewModel(
    private val repository: SessionRepository,
    private val connection: ServerConnectionManager,
    private val appScope: CoroutineScope,
    private val cwd: String?,
) : ViewModel() {

    val buffer = TerminalBuffer()

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var socket: WebSocket? = null
    private var ptyId: String? = null
    private var resizeJob: Job? = null

    init {
        open()
    }

    fun open() {
        close()
        _state.update { it.copy(status = TerminalUiState.Status.CONNECTING, error = null) }
        viewModelScope.launch {
            if (!connection.isConnected) connection.connect()
            val api = waitForApi() ?: run {
                fail("Not connected to an opencode server")
                return@launch
            }
            when (val created = repository.createPty(cwd)) {
                is ApiResult.Err -> fail(created.message)
                is ApiResult.Ok -> {
                    ptyId = created.value.id
                    val client = api.http.sseClient.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
                    val request = okhttp3.Request.Builder().url(api.http.url(api.ptyConnectPath(created.value.id))).build()
                    socket = client.newWebSocket(request, Listener())
                    pushSize()
                }
            }
        }
    }

    private suspend fun waitForApi() = run {
        repeat(30) {
            connection.api?.let { return@run it }
            delay(200)
        }
        connection.api
    }

    /** Raw keystrokes / control sequences to the shell. */
    fun send(data: String) {
        socket?.send(data)
    }

    fun resize(cols: Int, rows: Int) {
        if (cols == buffer.cols && rows == buffer.rows) return
        buffer.resize(cols, rows)
        _state.update { it.copy(version = buffer.version) }
        resizeJob?.cancel()
        resizeJob = viewModelScope.launch {
            delay(250)
            pushSize()
        }
    }

    private suspend fun pushSize() {
        val id = ptyId ?: return
        val api = connection.api ?: return
        runCatching { api.resizePty(id, buffer.rows, buffer.cols) }
    }

    private fun append(text: String) {
        viewModelScope.launch {
            buffer.write(text)
            _state.update { it.copy(version = buffer.version) }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(status = TerminalUiState.Status.FAILED, error = message) }
    }

    private fun close() {
        socket?.close(1000, null)
        socket = null
        val id = ptyId ?: return
        ptyId = null
        val api = connection.api ?: return
        // Fire and forget: the server reaps the PTY anyway when the socket closes.
        appScope.launch { runCatching { api.removePty(id) } }
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.update { it.copy(status = TerminalUiState.Status.OPEN, error = null) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = append(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // A leading 0x00 marks a control frame ({cursor}) that is not terminal output.
            if (bytes.size > 0 && bytes[0] == 0.toByte()) return
            append(bytes.utf8())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.update { it.copy(status = TerminalUiState.Status.CLOSED) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            fail(response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        fun factory(container: AppContainer, cwd: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TerminalViewModel(container.sessionRepository, container.connectionManager, container.appScope, cwd)
            }
        }
    }
}
