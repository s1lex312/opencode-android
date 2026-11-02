package ai.opencode.android.ui.chat

import ai.opencode.android.data.events.ChatStreamReducer
import ai.opencode.android.data.events.OpencodeEvent
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.data.model.PermissionRequest
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.SettingsRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessionId: String,
    /** Blank until the server reports the title; the screen shows a localised placeholder. */
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val loading: Boolean = true,
    val sending: Boolean = false,
    val busy: Boolean = false,
    val retryMessage: String? = null,
    val error: String? = null,
    val connected: Boolean = false,
    val connectionError: String? = null,
    val permissions: List<PermissionRequest> = emptyList(),
    /** Model/agent used for the next prompt (global defaults from Settings). */
    val model: ModelRef? = null,
    val agent: String = "",
    val models: List<ModelOption> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    val agents: List<AgentOption> = emptyList(),
) {
    val canSend: Boolean get() = connected && !sending
}

/**
 * Drives one conversation: loads the message projection, folds live SSE events through
 * [ChatStreamReducer], refetches after each turn and applies the configured permission policy.
 */
class ChatViewModel(
    private val repository: SessionRepository,
    private val connection: ServerConnectionManager,
    private val settingsRepository: SettingsRepository,
    private val sessionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(sessionId = sessionId))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var permissionPolicy: PermissionPolicy = PermissionPolicy.ASK
    private var model: ModelRef? = null
    private var agent: String = ""
    private var reloadJob: Job? = null
    private var titleLoadedFor: String? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                permissionPolicy = settings.permissionPolicy
                model = settings.model
                agent = settings.agent
                _state.update { it.copy(model = settings.model, agent = settings.agent) }
            }
        }
        viewModelScope.launch {
            connection.state.collect { state ->
                _state.update {
                    it.copy(
                        connected = state is ConnectionState.Connected,
                        connectionError = (state as? ConnectionState.Failed)?.message,
                    )
                }
                if (state is ConnectionState.Connected) {
                    loadTitleIfNeeded()
                    loadMessages(showSpinner = _state.value.messages.isEmpty())
                }
            }
        }
        viewModelScope.launch { connection.events.collect(::handleEvent) }
        if (!connection.isConnected) connection.connect()
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _state.value.sending) return
        val optimistic = ChatMessage(
            id = "local-${System.currentTimeMillis()}",
            role = Role.USER,
            created = System.currentTimeMillis(),
            parts = listOf(MessagePart.Text("local", trimmed)),
        )
        _state.update {
            it.copy(
                sending = true,
                busy = true,
                error = null,
                messages = it.messages + optimistic,
            )
        }
        viewModelScope.launch {
            when (val result = repository.sendPrompt(sessionId, trimmed, model, agent.ifBlank { null })) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(sending = false) }
                    scheduleReload(delayMs = 500)
                }

                is ApiResult.Err -> _state.update {
                    it.copy(
                        sending = false,
                        busy = false,
                        error = result.message,
                        messages = it.messages.filterNot { message -> message.id == optimistic.id },
                    )
                }
            }
        }
    }

    fun abort() {
        viewModelScope.launch {
            when (val result = repository.abort(sessionId)) {
                is ApiResult.Ok -> scheduleReload(delayMs = 300)
                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun replyPermission(request: PermissionRequest, decision: PermissionDecision) {
        _state.update { current -> current.copy(permissions = current.permissions.filterNot { it.id == request.id }) }
        viewModelScope.launch {
            when (val result = repository.replyPermission(sessionId, request.id, decision)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun loadModels() {
        if (_state.value.modelsLoading) return
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            when (val result = repository.models()) {
                is ApiResult.Ok -> _state.update { it.copy(modelsLoading = false, models = result.value) }
                is ApiResult.Err -> _state.update { it.copy(modelsLoading = false, modelsError = result.message) }
            }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            val result = repository.agents()
            if (result is ApiResult.Ok) {
                _state.update { it.copy(agents = result.value.filter { agent -> agent.selectable }.distinctBy { agent -> agent.name }) }
            }
        }
    }

    /** `null` = the server's default model. Persisted as the default for every chat. */
    fun selectModel(option: ModelOption?) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(model = option?.let { selected -> ModelRef(selected.providerID, selected.modelID) }) }
        }
    }

    /** Blank = the server's default agent. */
    fun selectAgent(name: String) {
        viewModelScope.launch { settingsRepository.update { it.copy(agent = name.trim()) } }
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        if (!connection.isConnected) connection.connect(force = true) else loadMessages(showSpinner = false)
    }

    private fun handleEvent(event: OpencodeEvent) {
        when (event) {
            is OpencodeEvent.PermissionAsked -> {
                if (event.sessionId == sessionId) onPermissionAsked(event.request)
            }

            is OpencodeEvent.PermissionResolved -> {
                if (event.sessionId == sessionId) {
                    _state.update { it.copy(permissions = it.permissions.filterNot { p -> p.id == event.permissionId }) }
                }
            }

            is OpencodeEvent.Status -> {
                if (event.sessionId == sessionId) {
                    _state.update { it.copy(busy = event.busy, retryMessage = event.retryMessage) }
                    if (!event.busy) scheduleReload()
                }
            }

            is OpencodeEvent.Idle -> {
                if (event.sessionId == sessionId) {
                    _state.update { it.copy(busy = false, retryMessage = null) }
                    scheduleReload()
                }
            }

            is OpencodeEvent.MessagesInvalidated -> {
                if (event.sessionId == null || event.sessionId == sessionId) scheduleReload()
            }

            is OpencodeEvent.SessionFailed -> {
                if (event.sessionId == null || event.sessionId == sessionId) {
                    _state.update { it.copy(busy = false, error = event.message) }
                }
            }

            is OpencodeEvent.SessionsChanged -> loadTitleIfNeeded(force = true)

            else -> {
                val updated = ChatStreamReducer.reduce(_state.value.messages, event, sessionId)
                if (updated != null) {
                    _state.update { it.copy(messages = updated, busy = true) }
                }
            }
        }
    }

    private fun onPermissionAsked(request: PermissionRequest) {
        when (permissionPolicy) {
            PermissionPolicy.ASK -> _state.update { current ->
                if (current.permissions.any { it.id == request.id }) current
                else current.copy(permissions = current.permissions + request)
            }

            PermissionPolicy.AUTO_ALLOW -> replyPermission(request, PermissionDecision.ONCE)
            PermissionPolicy.DENY -> replyPermission(request, PermissionDecision.REJECT)
        }
    }

    private fun scheduleReload(delayMs: Long = 200L) {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(delayMs)
            loadMessages(showSpinner = false)
        }
    }

    private fun loadMessages(showSpinner: Boolean) {
        if (showSpinner) _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val result = repository.messages(sessionId)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        messages = result.value,
                        loading = false,
                        error = null,
                    )
                }

                is ApiResult.Err -> _state.update {
                    it.copy(loading = false, error = if (result.notConnected) it.error else result.message)
                }
            }
        }
    }

    private fun loadTitleIfNeeded(force: Boolean = false) {
        if (!force && titleLoadedFor == sessionId) return
        viewModelScope.launch {
            val result = repository.loadSessions()
            if (result is ApiResult.Ok) {
                val match = result.value.firstOrNull { it.id == sessionId }
                if (match != null) {
                    titleLoadedFor = sessionId
                    _state.update { it.copy(title = match.title) }
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    repository = container.sessionRepository,
                    connection = container.connectionManager,
                    settingsRepository = container.settingsRepository,
                    sessionId = sessionId,
                )
            }
        }
    }
}
