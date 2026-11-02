package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.ui.text.UiText
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentSettingsUiState(
    val model: ModelRef? = null,
    /** Saved agent name; blank means the server default. */
    val agent: String = "",
    val permissionPolicy: PermissionPolicy = PermissionPolicy.ASK,
    val connected: Boolean = false,
    val models: List<ModelOption> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: UiText? = null,
    /** Agents that can drive a conversation (primary/all), as listed by the server. */
    val agents: List<AgentOption> = emptyList(),
    val agentsLoading: Boolean = false,
    val agentsLoaded: Boolean = false,
    val agentsError: UiText? = null,
)

/** Settings → Agent & model: default model, default agent and the permission policy. */
class AgentSettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val connectionManager: ServerConnectionManager,
    private val repository: SessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentSettingsUiState())
    val state: StateFlow<AgentSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _state.update {
                    it.copy(
                        model = settings.model,
                        agent = settings.agent,
                        permissionPolicy = settings.permissionPolicy,
                    )
                }
            }
        }
        viewModelScope.launch {
            connectionManager.state
                .map { it is ConnectionState.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    _state.update { it.copy(connected = connected) }
                    // Each (re)connection may point at a different server with other agents/models.
                    if (connected) {
                        loadAgents()
                        loadModels()
                    }
                }
        }
    }

    fun loadModels() {
        if (_state.value.modelsLoading) return
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            when (val result = repository.models()) {
                is ApiResult.Ok -> _state.update {
                    it.copy(modelsLoading = false, models = result.value, modelsError = null)
                }

                is ApiResult.Err -> _state.update {
                    it.copy(modelsLoading = false, modelsError = errorText(result, R.string.settings_models_offline))
                }
            }
        }
    }

    fun loadAgents() {
        if (_state.value.agentsLoading) return
        _state.update { it.copy(agentsLoading = true, agentsError = null) }
        viewModelScope.launch {
            when (val result = repository.agents()) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        agentsLoading = false,
                        agentsLoaded = true,
                        agents = result.value.filter { agent -> agent.selectable }.distinctBy { agent -> agent.name },
                        agentsError = null,
                    )
                }

                is ApiResult.Err -> _state.update {
                    it.copy(
                        agentsLoading = false,
                        agentsLoaded = true,
                        agentsError = errorText(result, R.string.settings_agents_offline),
                    )
                }
            }
        }
    }

    /** `null` selects the server's default model. */
    fun selectModel(option: ModelOption?) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(model = option?.let { selected -> ModelRef(selected.providerID, selected.modelID) })
            }
        }
    }

    /** A blank [name] selects the server's default agent. */
    fun selectAgent(name: String) {
        viewModelScope.launch { settingsRepository.update { it.copy(agent = name.trim()) } }
    }

    fun setPermissionPolicy(policy: PermissionPolicy) {
        viewModelScope.launch { settingsRepository.update { it.copy(permissionPolicy = policy) } }
    }

    private fun errorText(error: ApiResult.Err, @StringRes offlineMessage: Int): UiText =
        if (error.notConnected) UiText.of(offlineMessage) else UiText.raw(error.message)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AgentSettingsViewModel(
                    settingsRepository = container.settingsRepository,
                    connectionManager = container.connectionManager,
                    repository = container.sessionRepository,
                )
            }
        }
    }
}
