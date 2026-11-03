package ai.opencode.android.ui.logs

import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalServerState
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

data class LogsUiState(
    val lines: List<String> = emptyList(),
    val server: LocalServerState = LocalServerState(),
    val logPath: String = "",
    val fromFile: Boolean = false,
)

/**
 * Shows the local server log: live lines from the supervisor plus the tail of the on-disk log that
 * survives process restarts.
 */
class LogsViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState(logPath = container.localServerManager.logFilePath))
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.localServerManager.state.collect { server ->
                _state.update { current ->
                    if (server.logs.isNotEmpty()) {
                        current.copy(server = server, lines = server.logs, fromFile = false)
                    } else {
                        current.copy(
                            server = server,
                            lines = container.localServerManager.logLines(500),
                            fromFile = true,
                        )
                    }
                }
            }
        }
    }

    fun refresh() {
        _state.update {
            it.copy(
                lines = container.localServerManager.logLines(500),
                logPath = container.localServerManager.logFilePath,
                fromFile = true,
            )
        }
    }

    /** This app's own logcat, newest last. */
    fun appLog(): List<String> = ai.opencode.android.data.diagnostics.Diagnostics(container).appLog().lines()

    fun clear() {
        container.localServerManager.clearLogs()
        _state.update { it.copy(lines = emptyList()) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { LogsViewModel(container) }
        }
    }
}
