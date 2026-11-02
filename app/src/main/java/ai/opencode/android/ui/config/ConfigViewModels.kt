package ai.opencode.android.ui.config

import ai.opencode.android.R
import ai.opencode.android.bridge.PhoneAccessManager
import ai.opencode.android.bridge.PhoneAccessState
import ai.opencode.android.bridge.StorageAccess
import ai.opencode.android.data.events.OpencodeEvent
import ai.opencode.android.data.model.AgentDetail
import ai.opencode.android.data.model.BehaviourConfig
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.McpConfig
import ai.opencode.android.data.model.McpServerInfo
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.remote.ConfigJson
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.AppSettings
import ai.opencode.android.ui.text.UiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject

/** App-side preferences (chat look, phone access): the settings store and nothing else. */
class LocalPrefsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val phone: StateFlow<PhoneAccessState> = container.phoneAccess.state

    private val _storageGranted = MutableStateFlow(StorageAccess.granted(container.context))
    val storageGranted: StateFlow<Boolean> = _storageGranted.asStateFlow()

    val toolNames: List<String> get() = container.phoneAccess.tools.names

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsRepository.update(transform) }
    }

    /** Re-checked when the screen resumes (the user may come back from system settings). */
    fun refreshGrant() {
        _storageGranted.value = StorageAccess.granted(container.context)
    }

    /** Bind mounts only change when proot starts again. */
    fun restartLocalServer() = container.restartLocalServerService()

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { LocalPrefsViewModel(container) }
        }
    }
}

// ---------------------------------------------------------------------------------- MCP

data class McpUiState(
    val loading: Boolean = true,
    val servers: List<McpServerInfo> = emptyList(),
    val error: String? = null,
    val message: UiText? = null,
    /** Names with a request in flight (connect, auth...). */
    val working: Set<String> = emptySet(),
    val connected: Boolean = false,
    /** URL the server could not open itself (MCP OAuth); the screen opens it once. */
    val pendingAuthUrl: String? = null,
)

class McpViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.sessionRepository
    private val _state = MutableStateFlow(McpUiState())
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.connectionManager.state.collect { connection ->
                val connected = connection is ConnectionState.Connected
                _state.update { it.copy(connected = connected) }
                if (connected) refresh()
            }
        }
        viewModelScope.launch {
            container.connectionManager.events.collect { event ->
                when (event) {
                    is OpencodeEvent.McpAuthUrl -> _state.update { it.copy(pendingAuthUrl = event.url) }
                    is OpencodeEvent.McpChanged -> refresh(silent = true)
                    else -> Unit
                }
            }
        }
    }

    fun refresh(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val result = repository.mcpServers()) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, servers = result.value, error = null) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = result.message) }
            }
        }
    }

    fun authUrlOpened() = _state.update { it.copy(pendingAuthUrl = null) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun work(name: String, success: UiText?, block: suspend () -> ApiResult<*>) {
        _state.update { it.copy(working = it.working + name) }
        viewModelScope.launch {
            val result = block()
            _state.update {
                it.copy(
                    working = it.working - name,
                    message = when (result) {
                        is ApiResult.Ok -> success
                        is ApiResult.Err -> UiText.raw(result.message)
                    },
                )
            }
            refresh(silent = true)
        }
    }

    fun toggle(server: McpServerInfo, enable: Boolean) {
        val config = server.config
        if (config != null) {
            // Persist the choice so it survives a restart, then (dis)connect live.
            val updated = when (config) {
                is McpConfig.Local -> config.copy(enabled = enable)
                is McpConfig.Remote -> config.copy(enabled = enable)
            }
            work(server.name, null) {
                val saved = repository.mcpSave(server.name, updated)
                if (saved is ApiResult.Err || enable) saved else repository.mcpDisconnect(server.name)
            }
        } else {
            work(server.name, null) { if (enable) repository.mcpConnect(server.name) else repository.mcpDisconnect(server.name) }
        }
    }

    fun reconnect(name: String) = work(name, UiText.of(R.string.mcp_reconnected)) {
        repository.mcpDisconnect(name)
        repository.mcpConnect(name)
    }

    fun save(name: String, config: McpConfig, previousName: String?) {
        work(name, UiText.of(R.string.mcp_saved, name)) {
            val result = repository.mcpSave(name, config)
            if (result is ApiResult.Ok && previousName != null && previousName != name) removeQuietly(previousName)
            result
        }
    }

    fun remove(name: String) {
        work(name, null) {
            when (val result = repository.mcpRemove(name)) {
                is ApiResult.Err -> result
                is ApiResult.Ok -> {
                    val removed = result.value || removeFromLocalFile(name)
                    _state.update { it.copy(message = UiText.of(if (removed) R.string.mcp_removed else R.string.mcp_disabled_instead, name)) }
                    ApiResult.Ok(Unit)
                }
            }
        }
    }

    private suspend fun removeQuietly(name: String) {
        if (repository.mcpRemove(name).let { it is ApiResult.Ok && !it.value }) removeFromLocalFile(name)
    }

    /** 1.x cannot delete over HTTP; on the phone the config file is ours to edit. */
    private suspend fun removeFromLocalFile(name: String): Boolean {
        val settings = container.settingsRepository.current()
        if (!settings.isLocalMode) return false
        val removed = container.localConfigFile.remove(listOf("mcp", name))
        if (removed) container.restartLocalServerService()
        return removed
    }

    fun authenticate(name: String) = work(name, UiText.of(R.string.mcp_auth_done, name)) { repository.mcpAuthenticate(name) }

    fun submitCode(name: String, code: String) = work(name, UiText.of(R.string.mcp_auth_done, name)) { repository.mcpAuthCode(name, code.trim()) }

    fun logout(name: String) = work(name, UiText.of(R.string.mcp_logged_out, name)) { repository.mcpLogout(name) }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { McpViewModel(container) }
        }
    }
}

// ---------------------------------------------------------------------------------- agents

data class AgentsUiState(
    val loading: Boolean = true,
    val agents: List<AgentDetail> = emptyList(),
    val tools: List<String> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val canEdit: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val message: UiText? = null,
)

class AgentsViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.sessionRepository
    private val _state = MutableStateFlow(AgentsUiState())
    val state: StateFlow<AgentsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.connectionManager.state.collect { if (it is ConnectionState.Connected) refresh() }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val agents = repository.agentDetails()
            val tools = (repository.toolIds() as? ApiResult.Ok)?.value.orEmpty()
            val models = (repository.models() as? ApiResult.Ok)?.value.orEmpty()
            _state.update {
                it.copy(
                    loading = false,
                    agents = (agents as? ApiResult.Ok)?.value.orEmpty().sortedWith(compareBy({ a -> a.isSubagent }, { a -> a.name })),
                    tools = tools,
                    models = models,
                    canEdit = repository.supportsConfig == true,
                    error = (agents as? ApiResult.Err)?.message,
                )
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun save(agent: AgentDetail, onDone: () -> Unit) {
        val name = agent.name.trim()
        if (!NAME.matches(name)) {
            _state.update { it.copy(message = UiText.of(R.string.agents_name_invalid)) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val patch = buildJsonObject {
                put("agent", buildJsonObject { put(name, ConfigJson.encodeAgent(agent.copy(name = name))) })
            }
            when (val result = repository.patchGlobalConfig(patch)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(saving = false, message = UiText.of(R.string.agents_saved, name)) }
                    onDone()
                    refresh()
                }

                is ApiResult.Err -> _state.update { it.copy(saving = false, message = UiText.raw(result.message)) }
            }
        }
    }

    /** Drops the user's override (Local mode edits the file and restarts; otherwise disables). */
    fun reset(agent: AgentDetail) {
        viewModelScope.launch {
            val local = container.settingsRepository.current().isLocalMode
            if (local && container.localConfigFile.remove(listOf("agent", agent.name))) {
                container.restartLocalServerService()
                _state.update { it.copy(message = UiText.of(R.string.agents_reset_done, agent.name)) }
            } else {
                _state.update { it.copy(message = UiText.of(R.string.agents_reset_unavailable)) }
            }
        }
    }

    companion object {
        val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,48}$")

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AgentsViewModel(container) }
        }
    }
}

// ---------------------------------------------------------------------------------- behaviour

data class BehaviourUiState(
    val loading: Boolean = true,
    val config: BehaviourConfig = BehaviourConfig(),
    /** Last saved value, to show the Save button only when something changed. */
    val saved: BehaviourConfig = BehaviourConfig(),
    val tools: List<String> = emptyList(),
    val models: List<ModelOption> = emptyList(),
    val agents: List<String> = emptyList(),
    val canEdit: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val message: UiText? = null,
) {
    val dirty: Boolean get() = config != saved
}

class BehaviourViewModel(private val container: AppContainer) : ViewModel() {

    private val repository = container.sessionRepository
    private val _state = MutableStateFlow(BehaviourUiState())
    val state: StateFlow<BehaviourUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.connectionManager.state.collect { if (it is ConnectionState.Connected) refresh() }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val canEdit = repository.supportsConfig == true
            val config = if (canEdit) repository.globalConfig() else null
            val behaviour = (config as? ApiResult.Ok)?.value?.let(ConfigJson::decodeBehaviour) ?: BehaviourConfig()
            val tools = (repository.toolIds() as? ApiResult.Ok)?.value.orEmpty()
            val models = (repository.models() as? ApiResult.Ok)?.value.orEmpty()
            val agents = (repository.agents() as? ApiResult.Ok)?.value.orEmpty().filter { it.selectable }.map { it.name }
            _state.update {
                it.copy(
                    loading = false,
                    config = behaviour,
                    saved = behaviour,
                    tools = tools,
                    models = models,
                    agents = agents,
                    canEdit = canEdit,
                    error = (config as? ApiResult.Err)?.message,
                )
            }
        }
    }

    fun edit(transform: (BehaviourConfig) -> BehaviourConfig) = _state.update { it.copy(config = transform(it.config)) }

    fun messageShown() = _state.update { it.copy(message = null) }

    fun save() {
        val config = _state.value.config
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val result = repository.patchGlobalConfig(ConfigJson.encodeBehaviour(config))) {
                is ApiResult.Ok -> _state.update { it.copy(saving = false, saved = config, message = UiText.of(R.string.behaviour_saved)) }
                is ApiResult.Err -> _state.update { it.copy(saving = false, message = UiText.raw(result.message)) }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { BehaviourViewModel(container) }
        }
    }
}

/** Reference to keep the import used by screens that only need the constant. */
internal const val ANDROID_MCP = PhoneAccessManager.MCP_NAME
