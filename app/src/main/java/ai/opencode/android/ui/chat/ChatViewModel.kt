package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.data.events.ChatStreamReducer
import ai.opencode.android.data.events.OpencodeEvent
import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.data.model.PermissionRequest
import ai.opencode.android.data.model.PromptAttachment
import ai.opencode.android.data.model.PromptRequest
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.model.SlashCommand
import ai.opencode.android.data.model.TokenUsage
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.settings.SystemPromptProfile
import ai.opencode.android.ui.text.UiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A prompt typed while the agent was busy; sent automatically when the turn ends. */
data class QueuedPrompt(
    val id: Long,
    val text: String,
    val attachments: List<PromptAttachment> = emptyList(),
    val mentions: List<String> = emptyList(),
)

/** Context window accounting for the meter above the composer. */
data class ContextUsage(
    /** Tokens of the latest turn, i.e. what the next turn starts with. */
    val last: TokenUsage,
    /** Context window of the model, when the catalogue knows it. */
    val limit: Long?,
    /** Sum over every assistant turn of this session. */
    val total: TokenUsage,
    val cost: Double,
    val model: String?,
) {
    val used: Long get() = last.contextTokens

    /** 0..1, or `null` when the limit is unknown. */
    val fraction: Float? get() = limit?.takeIf { it > 0 }?.let { (used.toFloat() / it).coerceIn(0f, 1f) }
}

/** Entries of the `/` menu: server commands plus the client's own actions. */
data class CommandSuggestion(
    val name: String,
    val description: String?,
    val local: Boolean,
)

/** One-shot effects the screen performs (navigation, system share sheet, text restore). */
sealed interface ChatEffect {
    data class OpenSession(val sessionId: String) : ChatEffect
    data class ShareLink(val url: String) : ChatEffect
    data class RestoreInput(val text: String) : ChatEffect
    data class Toast(val text: UiText) : ChatEffect
}

data class ChatUiState(
    val sessionId: String,
    /** Blank until the server reports the title; the screen shows a localised placeholder. */
    val title: String = "",
    val directory: String? = null,
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
    val queue: List<QueuedPrompt> = emptyList(),
    val attachments: List<PromptAttachment> = emptyList(),
    val revertMessageId: String? = null,
    val shareUrl: String? = null,
    val sharing: Boolean = false,
    val compacting: Boolean = false,
    val commands: List<SlashCommand> = emptyList(),
    val fileSuggestions: List<String> = emptyList(),
    val systemPrompts: List<SystemPromptProfile> = emptyList(),
    val activeSystemPromptId: String? = null,
) {
    val canSend: Boolean get() = connected && !sending

    /** Messages shown in the transcript: everything before the revert point. */
    val visibleMessages: List<ChatMessage>
        get() {
            val revert = revertMessageId ?: return messages
            val index = messages.indexOfFirst { it.id == revert }
            return if (index < 0) messages else messages.subList(0, index)
        }

    val revertedCount: Int get() = messages.size - visibleMessages.size

    val activeSystemPrompt: SystemPromptProfile?
        get() = systemPrompts.firstOrNull { it.id == activeSystemPromptId }

    val usage: ContextUsage?
        get() {
            val turns = visibleMessages.filter { it.role == Role.ASSISTANT && it.tokens != null }
            val last = turns.lastOrNull() ?: return null
            val lastTokens = last.tokens ?: return null
            val catalogue = models.firstOrNull { it.providerID == last.providerId && it.modelID == last.model }
                ?: model?.let { selected -> models.firstOrNull { it.key == selected.key } }
            val total = turns.fold(TokenUsage()) { sum, message ->
                val tokens = message.tokens ?: return@fold sum
                TokenUsage(
                    input = sum.input + tokens.input,
                    output = sum.output + tokens.output,
                    reasoning = sum.reasoning + tokens.reasoning,
                    cacheRead = sum.cacheRead + tokens.cacheRead,
                    cacheWrite = sum.cacheWrite + tokens.cacheWrite,
                )
            }
            return ContextUsage(
                last = lastTokens,
                limit = catalogue?.contextLimit,
                total = total,
                cost = visibleMessages.sumOf { it.cost ?: 0.0 },
                model = last.model,
            )
        }

    /** Primary agents for the switcher above the composer, `build` and `plan` first. */
    val primaryAgents: List<AgentOption>
        get() {
            val selectable = agents.filter { it.selectable }
            val order = listOf("build", "plan")
            return selectable.sortedWith(compareBy({ order.indexOf(it.name).let { i -> if (i < 0) order.size else i } }, { it.name }))
        }
}

/**
 * Drives one conversation: loads the message projection, folds live SSE events through
 * [ChatStreamReducer], refetches after each turn and applies the configured permission policy.
 * Also owns the composer's helpers: prompt queue, attachments, `@` file mentions, `/` commands.
 */
class ChatViewModel(
    private val repository: SessionRepository,
    private val connection: ServerConnectionManager,
    private val settingsRepository: SettingsRepository,
    private val sessionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(sessionId = sessionId))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
    val effects: Flow<ChatEffect> = _effects.receiveAsFlow()

    private var permissionPolicy: PermissionPolicy = PermissionPolicy.ASK
    private var model: ModelRef? = null
    private var agent: String = ""
    private var systemPrompt: String? = null
    private var reloadJob: Job? = null
    private var fileSearchJob: Job? = null
    private var metaLoaded = false
    private var modelsValidated = false
    private var nextQueueId = 1L

    /** `@`-mentions the user picked from suggestions; sent as file parts when still in the text. */
    private val pickedMentions = linkedSetOf<String>()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                permissionPolicy = settings.permissionPolicy
                model = settings.model
                agent = settings.agent
                systemPrompt = settings.activeSystemPrompt?.text?.takeIf { it.isNotBlank() }
                _state.update {
                    it.copy(
                        model = settings.model,
                        agent = settings.agent,
                        systemPrompts = settings.systemPrompts,
                        activeSystemPromptId = settings.activeSystemPromptId,
                    )
                }
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
                    if (!metaLoaded) loadSessionMeta()
                    loadMessages(showSpinner = _state.value.messages.isEmpty())
                    if (_state.value.models.isEmpty()) loadModels()
                    if (_state.value.agents.isEmpty()) loadAgents()
                    if (_state.value.commands.isEmpty()) loadCommands()
                    if (_state.value.directory == null) {
                        _state.update { it.copy(directory = state.info.directory) }
                    }
                }
            }
        }
        viewModelScope.launch { connection.events.collect(::handleEvent) }
        if (!connection.isConnected) connection.connect()
    }

    // ------------------------------------------------------------------ sending

    /** Entry point of the send button: slash commands, queueing while busy, or a plain prompt. */
    fun send(text: String) {
        val trimmed = text.trim()
        val attachments = _state.value.attachments
        if (trimmed.isEmpty() && attachments.isEmpty()) return

        if (trimmed.startsWith("/") && attachments.isEmpty()) {
            val name = trimmed.removePrefix("/").substringBefore(' ').trim()
            val arguments = trimmed.substringAfter(' ', "").trim()
            if (runLocalCommand(name)) return
            if (_state.value.commands.any { it.name == name }) {
                runServerCommand(name, arguments)
                return
            }
        }

        val mentions = currentMentions(trimmed)
        _state.update { it.copy(attachments = emptyList()) }
        pickedMentions.clear()
        val current = _state.value
        if (current.busy || current.sending) {
            val queued = QueuedPrompt(nextQueueId++, trimmed, attachments, mentions)
            _state.update { it.copy(queue = it.queue + queued) }
            return
        }
        dispatch(trimmed, attachments, mentions)
    }

    private fun dispatch(text: String, attachments: List<PromptAttachment>, mentions: List<String>) {
        val optimistic = ChatMessage(
            id = "local-${System.currentTimeMillis()}",
            role = Role.USER,
            created = System.currentTimeMillis(),
            parts = listOf(MessagePart.Text("local", text)) +
                attachments.map { MessagePart.File("local-${it.id}", it.name, it.mime) },
        )
        _state.update {
            it.copy(
                sending = true,
                busy = true,
                error = null,
                // A new prompt discards the reverted tail on the server, mirror that locally.
                messages = it.visibleMessages + optimistic,
                revertMessageId = null,
            )
        }
        val request = PromptRequest(
            text = text,
            model = model,
            agent = agent.ifBlank { null },
            system = systemPrompt,
            attachments = attachments,
            mentionedFiles = mentions,
        )
        viewModelScope.launch {
            when (val result = repository.sendPrompt(sessionId, request)) {
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
                        attachments = attachments,
                    )
                }
            }
        }
    }

    /** Sends the next queued prompt once the agent is idle. */
    private fun drainQueue() {
        val current = _state.value
        if (current.busy || current.sending || !current.connected) return
        val next = current.queue.firstOrNull() ?: return
        _state.update { it.copy(queue = it.queue.drop(1)) }
        dispatch(next.text, next.attachments, next.mentions)
    }

    fun removeQueued(id: Long) = _state.update { it.copy(queue = it.queue.filterNot { q -> q.id == id }) }

    /** Moves a queued prompt back into the composer for editing. */
    fun editQueued(id: Long) {
        val queued = _state.value.queue.firstOrNull { it.id == id } ?: return
        removeQueued(id)
        _state.update { it.copy(attachments = it.attachments + queued.attachments) }
        _effects.trySend(ChatEffect.RestoreInput(queued.text))
    }

    fun abort() {
        viewModelScope.launch {
            when (val result = repository.abort(sessionId)) {
                is ApiResult.Ok -> scheduleReload(delayMs = 300)
                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    // ------------------------------------------------------------------ attachments & mentions

    fun addAttachment(attachment: PromptAttachment) =
        _state.update { it.copy(attachments = it.attachments + attachment) }

    fun removeAttachment(id: String) =
        _state.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }

    fun attachmentError(message: UiText) {
        _effects.trySend(ChatEffect.Toast(message))
    }

    /** Debounced project file search for the `@` popup; blank clears the suggestions. */
    fun searchFiles(query: String?) {
        fileSearchJob?.cancel()
        if (query == null) {
            _state.update { it.copy(fileSuggestions = emptyList()) }
            return
        }
        fileSearchJob = viewModelScope.launch {
            delay(FILE_SEARCH_DEBOUNCE_MS)
            val result = repository.findFiles(query)
            _state.update { it.copy(fileSuggestions = (result as? ApiResult.Ok)?.value.orEmpty().take(MAX_FILE_SUGGESTIONS)) }
        }
    }

    fun pickMention(path: String) {
        pickedMentions += path
        _state.update { it.copy(fileSuggestions = emptyList()) }
    }

    private fun currentMentions(text: String): List<String> {
        val directory = _state.value.directory?.trimEnd('/') ?: return emptyList()
        return pickedMentions.filter { "@$it" in text }.map { "$directory/${it.trimStart('/')}" }
    }

    // ------------------------------------------------------------------ slash commands

    val localCommands: List<String> = listOf(CMD_COMPACT, CMD_UNDO, CMD_REDO, CMD_FORK, CMD_SHARE)

    fun commandSuggestions(prefix: String): List<CommandSuggestion> {
        val local = localCommands.map { CommandSuggestion(it, null, local = true) }
        val server = _state.value.commands.map { CommandSuggestion(it.name, it.description, local = false) }
        return (server + local.filter { l -> server.none { it.name == l.name } })
            .filter { it.name.startsWith(prefix, ignoreCase = true) }
            .take(MAX_COMMAND_SUGGESTIONS)
    }

    private fun runLocalCommand(name: String): Boolean {
        if (_state.value.commands.any { it.name == name }) return false
        when (name) {
            CMD_COMPACT -> compact()
            CMD_UNDO -> _state.value.visibleMessages.lastOrNull { it.role == Role.USER }?.let(::revertTo)
            CMD_REDO -> unrevert()
            CMD_FORK -> forkFrom(null)
            CMD_SHARE -> share()
            else -> return false
        }
        return true
    }

    private fun runServerCommand(name: String, arguments: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.runCommand(sessionId, name, arguments, model, agent.ifBlank { null })) {
                is ApiResult.Ok -> scheduleReload()
                is ApiResult.Err -> _state.update { it.copy(busy = false, error = result.message) }
            }
        }
    }

    fun loadCommands() {
        viewModelScope.launch {
            val result = repository.commands()
            if (result is ApiResult.Ok) _state.update { it.copy(commands = result.value.distinctBy { c -> c.name }) }
        }
    }

    // ------------------------------------------------------------------ history: revert / fork / share

    /** Hides [message] and everything after it, restores files; a user prompt goes back to the composer. */
    fun revertTo(message: ChatMessage) {
        viewModelScope.launch {
            when (val result = repository.revert(sessionId, message.id)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(revertMessageId = message.id) }
                    if (message.role == Role.USER) _effects.trySend(ChatEffect.RestoreInput(message.plainText))
                    loadSessionMeta()
                }

                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun unrevert() {
        viewModelScope.launch {
            when (val result = repository.unrevert(sessionId)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(revertMessageId = null) }
                    scheduleReload()
                }

                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    /** New chat with the history up to and including [message] (the whole chat when `null`). */
    fun forkFrom(message: ChatMessage?) {
        val visible = _state.value.visibleMessages.filterNot { it.id.startsWith("local-") }
        val before = message?.let { selected ->
            val index = visible.indexOfFirst { it.id == selected.id }
            visible.getOrNull(index + 1)?.id
        }
        viewModelScope.launch {
            when (val result = repository.fork(sessionId, before)) {
                is ApiResult.Ok -> _effects.trySend(ChatEffect.OpenSession(result.value.id))
                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun share() {
        val existing = _state.value.shareUrl
        if (existing != null) {
            _effects.trySend(ChatEffect.ShareLink(existing))
            return
        }
        _state.update { it.copy(sharing = true) }
        viewModelScope.launch {
            when (val result = repository.share(sessionId)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(sharing = false, shareUrl = result.value) }
                    _effects.trySend(ChatEffect.ShareLink(result.value))
                }

                is ApiResult.Err -> _state.update { it.copy(sharing = false, error = result.message) }
            }
        }
    }

    fun unshare() {
        viewModelScope.launch {
            when (val result = repository.unshare(sessionId)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(shareUrl = null) }
                    _effects.trySend(ChatEffect.Toast(UiText.of(R.string.chat_share_revoked)))
                }

                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun compact() {
        if (_state.value.compacting) return
        _state.update { it.copy(compacting = true, busy = true) }
        viewModelScope.launch {
            when (val result = repository.compact(sessionId, model)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(compacting = false) }
                    scheduleReload()
                }

                is ApiResult.Err -> _state.update { it.copy(compacting = false, busy = false, error = result.message) }
            }
        }
    }

    // ------------------------------------------------------------------ permissions

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

    // ------------------------------------------------------------------ model / agent / system prompt

    fun loadModels() {
        if (_state.value.modelsLoading) return
        _state.update { it.copy(modelsLoading = true, modelsError = null) }
        viewModelScope.launch {
            when (val result = repository.models()) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(modelsLoading = false, models = result.value) }
                    validateSelectedModel(result.value)
                }

                is ApiResult.Err -> _state.update { it.copy(modelsLoading = false, modelsError = result.message) }
            }
        }
    }

    /**
     * The catalogue is live (models.dev via the server), so a saved model can disappear. Fall back
     * to the server default instead of failing every prompt with "model not found".
     */
    private fun validateSelectedModel(models: List<ModelOption>) {
        if (modelsValidated || models.isEmpty()) return
        modelsValidated = true
        val selected = model ?: return
        val available = models.any { it.providerID == selected.providerID && it.modelID == selected.modelID && !it.disabled }
        if (available) return
        viewModelScope.launch {
            settingsRepository.update { it.copy(model = null) }
            _effects.trySend(ChatEffect.Toast(UiText.of(R.string.chat_model_unavailable, selected.key)))
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

    fun selectSystemPrompt(id: String?) {
        viewModelScope.launch { settingsRepository.update { it.copy(activeSystemPromptId = id) } }
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        if (!connection.isConnected) connection.connect(force = true) else loadMessages(showSpinner = false)
    }

    // ------------------------------------------------------------------ events

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
                    if (!event.busy) onTurnFinished()
                }
            }

            is OpencodeEvent.Idle -> {
                if (event.sessionId == sessionId) {
                    _state.update { it.copy(busy = false, retryMessage = null, compacting = false) }
                    onTurnFinished()
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

            is OpencodeEvent.SessionsChanged -> loadSessionMeta()

            else -> {
                val updated = ChatStreamReducer.reduce(_state.value.messages, event, sessionId)
                if (updated != null) {
                    _state.update { it.copy(messages = updated, busy = true) }
                }
            }
        }
    }

    private fun onTurnFinished() {
        scheduleReload()
        viewModelScope.launch {
            // Let the final reload land before the next queued prompt starts a new turn.
            delay(QUEUE_DRAIN_DELAY_MS)
            drainQueue()
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

    /** Title, directory, share link and revert point. */
    private fun loadSessionMeta() {
        viewModelScope.launch {
            val session: ServerSession? = when (val direct = repository.session(sessionId)) {
                is ApiResult.Ok -> direct.value
                is ApiResult.Err -> (repository.loadSessions() as? ApiResult.Ok)?.value?.firstOrNull { it.id == sessionId }
            }
            if (session != null) {
                metaLoaded = true
                _state.update {
                    it.copy(
                        title = session.title,
                        directory = session.directory ?: it.directory,
                        shareUrl = session.shareUrl,
                        revertMessageId = session.revertMessageId,
                    )
                }
            }
        }
    }

    companion object {
        const val CMD_COMPACT = "compact"
        const val CMD_UNDO = "undo"
        const val CMD_REDO = "redo"
        const val CMD_FORK = "fork"
        const val CMD_SHARE = "share"

        private const val FILE_SEARCH_DEBOUNCE_MS = 180L
        private const val MAX_FILE_SUGGESTIONS = 8
        private const val MAX_COMMAND_SUGGESTIONS = 8
        private const val QUEUE_DRAIN_DELAY_MS = 600L

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
