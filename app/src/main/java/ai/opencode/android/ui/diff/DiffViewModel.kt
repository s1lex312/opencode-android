package ai.opencode.android.ui.diff

import ai.opencode.android.data.diff.DiffFile
import ai.opencode.android.data.diff.UnifiedDiffParser
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.di.AppContainer
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

/** One file of the session diff, already parsed for rendering. */
data class DiffFileView(
    val path: String,
    val status: String?,
    val additionCount: Int,
    val deletionCount: Int,
    val parsed: DiffFile,
    val rawPatch: String,
)

data class DiffUiState(
    val sessionId: String,
    val loading: Boolean = true,
    val files: List<DiffFileView> = emptyList(),
    val error: String? = null,
    val connected: Boolean = false,
    val refreshedAt: Long = 0L,
)

class DiffViewModel(
    private val repository: SessionRepository,
    private val connection: ServerConnectionManager,
    private val sessionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DiffUiState(sessionId = sessionId))
    val state: StateFlow<DiffUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            connection.state.collect { state ->
                _state.update { it.copy(connected = state is ai.opencode.android.data.model.ConnectionState.Connected) }
            }
        }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.diff(sessionId)) {
                is ApiResult.Ok -> {
                    val views = result.value.map { payload ->
                        val parsedFiles = UnifiedDiffParser.parse(payload.patch)
                        val parsed = parsedFiles.firstOrNull() ?: DiffFile(
                            oldPath = null,
                            newPath = payload.file,
                            hunks = emptyList(),
                        )
                        DiffFileView(
                            path = payload.file.ifBlank { parsed.path },
                            status = payload.status,
                            additionCount = payload.additions ?: parsed.additions,
                            deletionCount = payload.deletions ?: parsed.deletions,
                            parsed = parsed,
                            rawPatch = payload.patch,
                        )
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            files = views,
                            error = null,
                            refreshedAt = System.currentTimeMillis(),
                        )
                    }
                }

                is ApiResult.Err -> _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DiffViewModel(
                    repository = container.sessionRepository,
                    connection = container.connectionManager,
                    sessionId = sessionId,
                )
            }
        }
    }
}
