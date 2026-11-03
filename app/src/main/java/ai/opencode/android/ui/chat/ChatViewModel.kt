package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.data.events.ChatStreamReducer
import ai.opencode.android.data.events.OpencodeEvent
import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.MentionCandidate
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.data.model.PermissionRequest
import ai.opencode.android.data.model.PromptAttachment
import ai.opencode.android.data.model.PromptRequest
import ai.opencode.android.data.model.QuestionRequest
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.hooks.HookRunner
import ai.opencode.android.data.share.DraftInbox
import ai.opencode.android.data.stats.BudgetStatus
import ai.opencode.android.data.stats.UsageStats
import ai.opencode.android.data.tasks.BackgroundTasks
import ai.opencode.android.settings.Snippet
import ai.opencode.android.settings.Workbench
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.model.SlashCommand
import ai.opencode.android.data.model.TokenUsage
import ai.opencode.android.data.model.ToolStatus
import ai.opencode.android.data.remote.ConfigJson
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.ChatSettings
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.settings.SystemPromptProfile
import ai.opencode.android.ui.text.UiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A prompt typed while the agent was busy; sent automatically when the turn ends. */
data class QueuedPrompt(
    val id: Long,
    val text: String,
    val attachments: List<PromptAttachment> = emptyList(),
    val mentions: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
)

/** Files changed by one user turn, for the checkpoint preview. */
data class CheckpointTurn(val message: ChatMessage, val files: List<FileDiffPayload>)

/** What reverting to [target] would undo; shown before the revert happens. */
data class RevertPreview(
    val target: ChatMessage,
    val loading: Boolean = true,
    val turns: List<CheckpointTurn> = emptyList(),
    /** The server cannot report per-turn diffs; the revert still works. */
    val unavailable: Boolean = false,
) {
    val fileCount: Int get() = turns.flatMap { turn -> turn.files.map { it.file } }.distinct().size
}

/** Where the context window size comes from. */
enum class LimitSource { CATALOGUE, MODEL, CHAT }

/** Context window accounting for the meter under the composer. */
data class ContextUsage(
    /** Tokens of the latest turn, i.e. what the next turn starts with. */
    val last: TokenUsage,
    /** Context window, from the user's override or the model catalogue. */
    val limit: Long?,
    /** Sum over every assistant turn of this session. */
    val total: TokenUsage,
    val cost: Double,
    val model: String?,
    val limitSource: LimitSource? = null,
    /** `provider/model` the limit applies to, for the per-model editor. */
    val modelKey: String? = null,
    /** Window the catalogue reports, shown next to an override. */
    val catalogueLimit: Long? = null,
) {
    val used: Long get() = last.contextTokens

    /** 0..1, or `null` when the limit is unknown. */
    val fraction: Float? get() = limit?.takeIf { it > 0 }?.let { (used.toFloat() / it).coerceIn(0f, 1f) }

    /** Percentage still free, like Claude Code's "Context left until auto-compact". */
    val percentLeft: Int? get() = fraction?.let { ((1f - it) * 100).toInt().coerceIn(0, 100) }
}

/** Entries of the `/` menu: server commands plus the client's own. */
data class CommandSuggestion(
    val name: String,
    val description: String?,
    val local: Boolean,
    val argHint: String? = null,
    val source: String? = null,
)

/** One-shot effects the screen performs (navigation, share sheet, text restore). */
sealed interface ChatEffect {
    data class OpenSession(val sessionId: String) : ChatEffect
    data class ShareLink(val url: String) : ChatEffect
    data class ShareText(val title: String, val text: String) : ChatEffect
    data class Copy(val text: String) : ChatEffect
    data class RestoreInput(val text: String) : ChatEffect
    data class Toast(val text: UiText) : ChatEffect
    data class Navigate(val destination: ChatDestination) : ChatEffect
    data object OpenModelPicker : ChatEffect
    data object OpenUsage : ChatEffect
    data object OpenHelp : ChatEffect
    data object OpenCheckpoints : ChatEffect
    data object Close : ChatEffect
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
    /** When the current turn started (epoch ms), for the spinner's elapsed time. */
    val turnStartedAt: Long? = null,
    val retryMessage: String? = null,
    val error: String? = null,
    val connected: Boolean = false,
    val connectionError: String? = null,
    val serverVersion: String? = null,
    val permissions: List<PermissionRequest> = emptyList(),
    /** Model/agent used for the next prompt (global defaults from Settings). */
    val model: ModelRef? = null,
    val agent: String = "",
    val models: List<ModelOption> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String? = null,
    /** Every agent the server reports (primary and subagents). */
    val agents: List<AgentOption> = emptyList(),
    val queue: List<QueuedPrompt> = emptyList(),
    val attachments: List<PromptAttachment> = emptyList(),
    val revertMessageId: String? = null,
    val shareUrl: String? = null,
    val sharing: Boolean = false,
    val compacting: Boolean = false,
    val commands: List<SlashCommand> = emptyList(),
    val mentionSuggestions: List<MentionCandidate> = emptyList(),
    val systemPrompts: List<SystemPromptProfile> = emptyList(),
    val activeSystemPromptId: String? = null,
    val chat: ChatSettings = ChatSettings(),
    /** User override of the context window for this chat. */
    val sessionContextLimit: Long? = null,
    /** User overrides per model (`provider/model` → tokens). */
    val modelContextLimits: Map<String, Long> = emptyMap(),
    /** Questions the agent is blocked on (the `question` tool). */
    val questions: List<QuestionRequest> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val budget: BudgetStatus? = null,
    val revertPreview: RevertPreview? = null,
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

    /** Agent that runs the next prompt; blank `agent` means the server default (`build`). */
    val effectiveAgent: String
        get() = agent.ifBlank { primaryAgents.firstOrNull { it.name == "build" }?.name ?: primaryAgents.firstOrNull()?.name ?: "build" }

    val usage: ContextUsage?
        get() {
            val turns = visibleMessages.filter { it.role == Role.ASSISTANT && it.tokens != null }
            val last = turns.lastOrNull()
            val lastKey = last?.let { message ->
                val provider = message.providerId
                val id = message.model
                if (provider != null && id != null) "$provider/$id" else null
            }
            val modelKey = lastKey ?: model?.key
            val catalogue = models.firstOrNull { it.key == modelKey }
            val modelOverride = modelKey?.let { modelContextLimits[it] }
            val limit = sessionContextLimit ?: modelOverride ?: catalogue?.contextLimit
            if (last == null && limit == null) return null
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
                last = last?.tokens ?: TokenUsage(),
                limit = limit,
                total = total,
                cost = visibleMessages.sumOf { it.cost ?: 0.0 },
                model = last?.model ?: model?.modelID,
                limitSource = when {
                    sessionContextLimit != null -> LimitSource.CHAT
                    modelOverride != null -> LimitSource.MODEL
                    catalogue?.contextLimit != null -> LimitSource.CATALOGUE
                    else -> null
                },
                modelKey = modelKey,
                catalogueLimit = catalogue?.contextLimit,
            )
        }

    /** Primary agents for the mode switcher, `build` and `plan` first. */
    val primaryAgents: List<AgentOption>
        get() {
            val selectable = agents.filter { it.selectable }
            val order = listOf("build", "plan")
            return selectable.sortedWith(compareBy({ order.indexOf(it.name).let { i -> if (i < 0) order.size else i } }, { it.name }))
        }

    /** Agents the primary agent can delegate to, offered as `@name` mentions. */
    val subagents: List<AgentOption>
        get() = agents.filter { it.mode == "subagent" || it.mode == "all" }.sortedBy { it.name }
}

/**
 * Drives one conversation: loads the message projection, folds live SSE events through
 * [ChatStreamReducer], refetches after each turn and applies the configured permission policy.
 * Also owns the composer's helpers: prompt queue, attachments, `@` mentions, `/` and `!` input.
 */
class ChatViewModel(
    private val repository: SessionRepository,
    private val connection: ServerConnectionManager,
    private val settingsRepository: SettingsRepository,
    private val sessionId: String,
    private val hooks: HookRunner? = null,
    private val workbench: Workbench? = null,
    private val usageStats: UsageStats? = null,
    private val tasks: BackgroundTasks? = null,
    drafts: DraftInbox? = null,
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
    private var mentionSearchJob: Job? = null
    private var metaLoaded = false
    private var modelsValidated = false
    private var nextQueueId = 1L

    /** Set after an automatic compaction until the next prompt, so it cannot loop. */
    private var autoCompacted = false

    /** `@`-mentions the user picked from suggestions; sent as file parts when still in the text. */
    private val pickedMentions = linkedSetOf<String>()

    /** Budget warnings already shown, so each threshold toasts once per chat screen. */
    private var budgetWarned = false
    private var snippetDepth = 0
    private var budgetExceededShown = false

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
                        chat = settings.chat,
                        sessionContextLimit = settings.sessionContextLimits[sessionId],
                        modelContextLimits = settings.modelContextLimits,
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
                        serverVersion = (state as? ConnectionState.Connected)?.info?.version ?: it.serverVersion,
                    )
                }
                if (state is ConnectionState.Connected) {
                    if (!metaLoaded) loadSessionMeta()
                    loadMessages(showSpinner = _state.value.messages.isEmpty())
                    if (_state.value.models.isEmpty()) loadModels()
                    if (_state.value.agents.isEmpty()) loadAgents()
                    if (_state.value.commands.isEmpty()) loadCommands()
                    loadQuestions()
                    if (_state.value.directory == null) {
                        _state.update { it.copy(directory = state.info.directory) }
                    }
                }
            }
        }
        viewModelScope.launch { connection.events.collect(::handleEvent) }
        workbench?.let { bench ->
            viewModelScope.launch {
                bench.data.collect { data ->
                    _state.update { it.copy(snippets = data.snippets, budget = usageStats?.budgetStatus(data.budget)) }
                }
            }
        }
        usageStats?.let { stats ->
            viewModelScope.launch {
                stats.state.collect {
                    val budget = workbench?.current?.budget ?: return@collect
                    _state.update { it.copy(budget = stats.budgetStatus(budget)) }
                }
            }
        }
        drafts?.take(sessionId)?.let { draft ->
            _state.update { it.copy(attachments = it.attachments + draft.attachments) }
            if (draft.send) {
                viewModelScope.launch {
                    connection.state.first { it is ConnectionState.Connected }
                    send(draft.text)
                }
            } else {
                _effects.trySend(ChatEffect.RestoreInput(draft.text))
            }
        }
        // SSE can drop the final status event (network change, server restart): ask the server
        // directly every so often while a turn looks active, so the spinner never sticks.
        viewModelScope.launch {
            while (true) {
                delay(BUSY_POLL_MS)
                if (_state.value.busy && _state.value.connected) syncBusy()
            }
        }
        if (!connection.isConnected) connection.connect()
    }

    // ------------------------------------------------------------------ sending

    /**
     * Entry point of the send button:
     *  * `/name args` runs a client command, then a server command; unknown names are reported;
     *  * `!command` runs in the project shell (the TUI's shell mode);
     *  * everything else is a prompt, queued while the agent is busy.
     */
    fun send(text: String) {
        val trimmed = text.trim()
        val attachments = _state.value.attachments
        if (trimmed.isEmpty() && attachments.isEmpty()) return

        val command = parseCommand(trimmed)
        if (command != null && attachments.isEmpty()) {
            ClientCommand.find(command.name)?.let { client ->
                runClientCommand(client, command.arguments)
                return
            }
            _state.value.snippets.firstOrNull { it.name.equals(command.name, ignoreCase = true) }?.let { snippet ->
                runSnippet(snippet, command.arguments)
                return
            }
            if (_state.value.commands.any { it.name == command.name }) {
                runServerCommand(command.name, command.arguments)
                return
            }
            if (!looksLikePath(command.name)) {
                _effects.trySend(ChatEffect.Toast(UiText.of(R.string.chat_unknown_command, command.name)))
                _effects.trySend(ChatEffect.RestoreInput(text))
                return
            }
        }

        if (trimmed.startsWith("!") && trimmed.length > 1 && attachments.isEmpty()) {
            runShell(trimmed.substring(1).trim())
            return
        }

        if (budgetBlocks()) {
            _effects.trySend(ChatEffect.RestoreInput(text))
            return
        }

        val mentions = currentMentions(trimmed)
        val agents = mentionedAgents(trimmed)
        _state.update { it.copy(attachments = emptyList()) }
        pickedMentions.clear()
        val current = _state.value
        if (current.busy || current.sending) {
            val queued = QueuedPrompt(nextQueueId++, trimmed, attachments, mentions, agents)
            _state.update { it.copy(queue = it.queue + queued) }
            return
        }
        dispatch(trimmed, attachments, mentions, agents)
    }

    private fun dispatch(text: String, attachments: List<PromptAttachment>, mentions: List<String>, agents: List<String>) {
        autoCompacted = false
        val now = System.currentTimeMillis()
        val optimistic = ChatMessage(
            id = "local-$now",
            role = Role.USER,
            created = now,
            parts = listOf(MessagePart.Text("local", text)) +
                attachments.map { MessagePart.File("local-${it.id}", it.name, it.mime) },
        )
        _state.update {
            it.copy(
                sending = true,
                busy = true,
                turnStartedAt = now,
                error = null,
                // A new prompt discards the reverted tail on the server, mirror that locally.
                messages = it.visibleMessages + optimistic,
                revertMessageId = null,
            )
        }
        viewModelScope.launch {
            val hook = hooks?.beforePrompt(sessionId, _state.value.directory, text)
            if (hook?.blocked == true) {
                _state.update {
                    it.copy(
                        sending = false,
                        busy = false,
                        turnStartedAt = null,
                        messages = it.messages.filterNot { message -> message.id == optimistic.id },
                        attachments = attachments,
                    )
                }
                _effects.trySend(ChatEffect.Toast(UiText.of(R.string.hooks_prompt_blocked, hook.message.orEmpty())))
                _effects.trySend(ChatEffect.RestoreInput(text))
                return@launch
            }
            val request = PromptRequest(
                text = hook?.context?.let { context -> "$text\n\n<hook-context>\n$context\n</hook-context>" } ?: text,
                model = model,
                agent = agent.ifBlank { null },
                system = systemPrompt,
                attachments = attachments,
                mentionedFiles = mentions,
                mentionedAgents = agents,
            )
            when (val result = repository.sendPrompt(sessionId, request)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(sending = false) }
                    scheduleReload(delayMs = 500)
                }

                is ApiResult.Err -> _state.update {
                    it.copy(
                        sending = false,
                        busy = false,
                        turnStartedAt = null,
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
        dispatch(next.text, next.attachments, next.mentions, next.agents)
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

    /** `!command`: runs in the project shell and lands in the transcript like a tool call. */
    private fun runShell(command: String) {
        if (command.isBlank()) return
        val current = _state.value
        _state.update { it.copy(busy = true, turnStartedAt = System.currentTimeMillis(), error = null) }
        viewModelScope.launch {
            when (val result = repository.runShell(sessionId, command, current.effectiveAgent, model)) {
                is ApiResult.Ok -> scheduleReload()
                is ApiResult.Err -> {
                    _state.update { it.copy(busy = false, turnStartedAt = null, error = result.message) }
                    _effects.trySend(ChatEffect.RestoreInput("!$command"))
                }
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

    /**
     * Debounced `@` popup. Empty query: subagents plus the project root; `dir/`: that directory;
     * anything else: fuzzy file/directory search plus matching subagents. `null` closes the popup.
     */
    fun searchMentions(query: String?) {
        mentionSearchJob?.cancel()
        if (query == null) {
            _state.update { it.copy(mentionSuggestions = emptyList()) }
            return
        }
        mentionSearchJob = viewModelScope.launch {
            if (query.isNotEmpty()) delay(MENTION_DEBOUNCE_MS)
            val agents = _state.value.subagents
                .filter { query.isEmpty() || it.name.startsWith(query, ignoreCase = true) }
                .map { MentionCandidate(it.name, MentionCandidate.Kind.AGENT, it.description) }
            val paths = when {
                query.isEmpty() -> repository.listDirectory("")
                query.endsWith("/") -> repository.listDirectory(query.trimEnd('/'))
                else -> repository.findPaths(query)
            }
            val files = (paths as? ApiResult.Ok)?.value.orEmpty()
            _state.update { it.copy(mentionSuggestions = (agents + files).take(MAX_MENTION_SUGGESTIONS)) }
        }
    }

    fun pickMention(candidate: MentionCandidate) {
        if (candidate.kind != MentionCandidate.Kind.AGENT) pickedMentions += candidate.value.trimEnd('/')
        // Picking a directory keeps the popup open on its contents (handled by the caret token).
        if (candidate.kind != MentionCandidate.Kind.DIRECTORY) _state.update { it.copy(mentionSuggestions = emptyList()) }
    }

    private fun currentMentions(text: String): List<String> {
        val directory = _state.value.directory?.trimEnd('/') ?: return emptyList()
        return pickedMentions
            .filter { path -> MENTION_TOKEN.findAll(text).any { it.groupValues[1].trimEnd('/') == path } }
            .map { "$directory/${it.trimStart('/')}" }
    }

    private fun mentionedAgents(text: String): List<String> {
        val names = _state.value.subagents.map { it.name }.toSet()
        return MENTION_TOKEN.findAll(text).map { it.groupValues[1] }.filter { it in names }.distinct().toList()
    }

    // ------------------------------------------------------------------ slash commands

    fun commandSuggestions(prefix: String): List<CommandSuggestion> {
        val server = _state.value.commands
            .filter { ClientCommand.find(it.name) == null }
            .map { CommandSuggestion(it.name, it.description, local = false, argHint = it.hints.joinToString(" ").ifBlank { null }, source = it.source) }
        val client = ClientCommand.entries.map { CommandSuggestion(it.id, null, local = true, argHint = it.argHint) }
        val snippets = _state.value.snippets
            .filter { snippet -> ClientCommand.find(snippet.name) == null }
            .map { snippet ->
                CommandSuggestion(
                    name = snippet.name,
                    description = snippet.description.ifBlank { snippet.body.lineSequence().firstOrNull().orEmpty().take(80) },
                    local = false,
                    argHint = if (snippet.body.contains("\$ARGUMENTS") || snippet.body.contains("\$1")) "[args]" else null,
                    source = SNIPPET_SOURCE,
                )
            }
        val all = snippets + server.filter { command -> snippets.none { it.name == command.name } } + client
        if (prefix.isEmpty()) return all
        val lower = prefix.lowercase()
        val aliasHits = ClientCommand.entries
            .filter { command -> command.aliases.any { it.startsWith(lower) } && !command.id.startsWith(lower) }
            .map { CommandSuggestion(it.id, null, local = true, argHint = it.argHint) }
        return all.filter { it.name.startsWith(lower, ignoreCase = true) } +
            aliasHits +
            all.filter { !it.name.startsWith(lower, ignoreCase = true) && it.name.contains(lower, ignoreCase = true) }
    }

    /** Every command for the `/help` sheet. */
    fun allCommands(): List<CommandSuggestion> = commandSuggestions("")

    private fun runClientCommand(command: ClientCommand, arguments: String) {
        when (command) {
            ClientCommand.NEW -> newSession()
            ClientCommand.SESSIONS, ClientCommand.EXIT -> _effects.trySend(ChatEffect.Close)
            ClientCommand.MODELS -> if (arguments.isBlank()) _effects.trySend(ChatEffect.OpenModelPicker) else selectModelByName(arguments)
            ClientCommand.AGENT -> if (arguments.isBlank()) cycleAgent() else selectAgentByName(arguments)
            ClientCommand.COMPACT -> compact()
            ClientCommand.CONTEXT -> contextCommand(arguments)
            ClientCommand.UNDO -> _state.value.visibleMessages.lastOrNull { it.role == Role.USER }?.let(::revertTo)
                ?: toast(R.string.chat_nothing_to_undo)
            ClientCommand.REDO -> if (_state.value.revertMessageId != null) unrevert() else toast(R.string.chat_nothing_to_redo)
            ClientCommand.FORK -> forkFrom(null)
            ClientCommand.RENAME -> if (arguments.isBlank()) toast(R.string.chat_rename_usage) else rename(arguments)
            ClientCommand.SHARE -> share()
            ClientCommand.UNSHARE -> unshare()
            ClientCommand.EXPORT -> _effects.trySend(ChatEffect.ShareText(_state.value.title, exportMarkdown()))
            ClientCommand.COPY -> {
                val last = _state.value.visibleMessages.lastOrNull { it.role == Role.ASSISTANT && it.plainText.isNotBlank() }
                if (last != null) _effects.trySend(ChatEffect.Copy(last.plainText)) else toast(R.string.chat_nothing_to_copy)
            }
            ClientCommand.DETAILS -> toggleChat { it.copy(expandTools = !it.expandTools) }
            ClientCommand.THINKING -> toggleChat { it.copy(expandReasoning = !it.expandReasoning) }
            ClientCommand.SYSTEM -> systemCommand(arguments)
            ClientCommand.MCP -> _effects.trySend(ChatEffect.Navigate(ChatDestination.MCP))
            ClientCommand.PERMISSIONS -> _effects.trySend(ChatEffect.Navigate(ChatDestination.PERMISSIONS))
            ClientCommand.AGENTS_CONFIG -> _effects.trySend(ChatEffect.Navigate(ChatDestination.AGENTS))
            ClientCommand.TERMINAL -> _effects.trySend(ChatEffect.Navigate(ChatDestination.TERMINAL))
            ClientCommand.DIFF -> _effects.trySend(ChatEffect.Navigate(ChatDestination.DIFF))
            ClientCommand.SETTINGS -> _effects.trySend(ChatEffect.Navigate(ChatDestination.SETTINGS))
            ClientCommand.THEME -> _effects.trySend(ChatEffect.Navigate(ChatDestination.CHAT_SETTINGS))
            ClientCommand.STATUS -> statusToast()
            ClientCommand.HELP -> _effects.trySend(ChatEffect.OpenHelp)
            ClientCommand.REWIND -> _effects.trySend(ChatEffect.OpenCheckpoints)
            ClientCommand.GIT -> _effects.trySend(ChatEffect.Navigate(ChatDestination.GIT))
            ClientCommand.BACKGROUND ->
                if (arguments.isBlank()) _effects.trySend(ChatEffect.Navigate(ChatDestination.TASKS)) else startBackground(arguments)
            ClientCommand.TASKS -> _effects.trySend(ChatEffect.Navigate(ChatDestination.TASKS))
            ClientCommand.SNIPPETS -> _effects.trySend(ChatEffect.Navigate(ChatDestination.SNIPPETS))
            ClientCommand.HOOKS -> _effects.trySend(ChatEffect.Navigate(ChatDestination.HOOKS))
            ClientCommand.STATS -> _effects.trySend(ChatEffect.Navigate(ChatDestination.STATS))
            ClientCommand.ENV -> _effects.trySend(ChatEffect.Navigate(ChatDestination.ENV))
        }
    }

    /** `/name args` of a saved prompt: expanded, then sent or put into the composer. */
    private fun runSnippet(snippet: Snippet, arguments: String) {
        val expanded = Snippets.expand(snippet.body, arguments)
        if (expanded.isBlank()) return
        // A snippet may call another one; a cycle ends up in the composer instead of looping.
        if (snippet.send && snippetDepth < MAX_SNIPPET_DEPTH) {
            snippetDepth++
            try {
                send(expanded)
            } finally {
                snippetDepth--
            }
        } else {
            _effects.trySend(ChatEffect.RestoreInput(expanded))
        }
    }

    /** `/bg prompt`: a new chat that runs on its own and notifies with the answer. */
    private fun startBackground(prompt: String) {
        val runner = tasks ?: return
        viewModelScope.launch {
            when (val result = runner.launch(prompt, model = model, agent = agent.ifBlank { null })) {
                is ApiResult.Ok -> toast(R.string.task_started)
                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    /** True (and tells the user) when the budget is spent and blocking is on. */
    private fun budgetBlocks(): Boolean {
        val budget = _state.value.budget ?: return false
        if (workbench?.current?.budget?.block != true || !budget.exceeded) return false
        toast(R.string.budget_blocked, formatCost(budget.daily), formatCost(budget.monthly))
        return true
    }

    private fun checkBudget() {
        val budget = _state.value.budget ?: return
        when {
            budget.exceeded && !budgetExceededShown -> {
                budgetExceededShown = true
                toast(R.string.budget_exceeded, formatCost(budget.daily), formatCost(budget.monthly))
            }
            budget.warning && !budgetWarned -> {
                budgetWarned = true
                toast(R.string.budget_warning, (maxOf(budget.dailyRatio, budget.monthlyRatio) * 100).toInt())
            }
        }
    }

    private fun toast(res: Int, vararg args: Any) {
        _effects.trySend(ChatEffect.Toast(UiText.of(res, *args)))
    }

    private fun runServerCommand(name: String, arguments: String) {
        autoCompacted = false
        _state.update { it.copy(busy = true, turnStartedAt = System.currentTimeMillis(), error = null) }
        viewModelScope.launch {
            // Answers when the turn is over; the transcript streams over SSE meanwhile.
            when (val result = repository.runCommand(sessionId, name, arguments, model, agent.ifBlank { null })) {
                is ApiResult.Ok -> scheduleReload()
                is ApiResult.Err -> _state.update { it.copy(busy = false, turnStartedAt = null, error = result.message) }
            }
        }
    }

    fun loadCommands() {
        viewModelScope.launch {
            val result = repository.commands()
            if (result is ApiResult.Ok) _state.update { it.copy(commands = result.value.distinctBy { c -> c.name }) }
        }
    }

    private fun newSession() {
        viewModelScope.launch {
            when (val result = repository.createSession()) {
                is ApiResult.Ok -> _effects.trySend(ChatEffect.OpenSession(result.value.id))
                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    private fun selectModelByName(raw: String) {
        val query = raw.trim()
        val models = _state.value.models
        val match = models.firstOrNull { it.key.equals(query, ignoreCase = true) }
            ?: models.firstOrNull { it.modelID.equals(query, ignoreCase = true) }
            ?: models.filter { it.key.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true) }.singleOrNull()
            ?: ConfigJson.parseModel(query)?.let { ref -> ModelOption(ref.providerID, ref.modelID, ref.modelID) }
        if (match == null) {
            toast(R.string.chat_model_not_found, query)
            _effects.trySend(ChatEffect.OpenModelPicker)
            return
        }
        selectModel(match)
        toast(R.string.chat_model_switched, match.key)
    }

    private fun selectAgentByName(raw: String) {
        val name = raw.trim().removePrefix("@")
        val agent = _state.value.primaryAgents.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (agent == null) {
            toast(R.string.chat_agent_not_found, name)
            return
        }
        selectAgent(agent.name)
    }

    /** Next primary agent, like Claude Code's shift+tab mode cycle. */
    fun cycleAgent() {
        val agents = _state.value.primaryAgents
        if (agents.isEmpty()) return
        val current = _state.value.effectiveAgent
        val index = agents.indexOfFirst { it.name == current }
        selectAgent(agents[(index + 1).mod(agents.size)].name)
    }

    private fun contextCommand(arguments: String) {
        val arg = arguments.trim().lowercase()
        when {
            arg.isEmpty() -> _effects.trySend(ChatEffect.OpenUsage)
            arg == "reset" || arg == "auto" || arg == "default" -> setSessionContextLimit(null)
            else -> {
                val tokens = parseTokenCount(arg)
                if (tokens == null) toast(R.string.chat_context_usage) else setSessionContextLimit(tokens)
            }
        }
    }

    private fun systemCommand(arguments: String) {
        val arg = arguments.trim()
        when {
            arg.isEmpty() -> _effects.trySend(ChatEffect.Navigate(ChatDestination.SYSTEM_PROMPTS))
            arg.equals("off", ignoreCase = true) || arg.equals("none", ignoreCase = true) -> selectSystemPrompt(null)
            else -> {
                val prompt = _state.value.systemPrompts.firstOrNull { it.name.equals(arg, ignoreCase = true) }
                    ?: _state.value.systemPrompts.firstOrNull { it.name.contains(arg, ignoreCase = true) }
                if (prompt == null) toast(R.string.chat_system_prompt_not_found, arg) else selectSystemPrompt(prompt.id)
            }
        }
    }

    private fun toggleChat(transform: (ChatSettings) -> ChatSettings) {
        viewModelScope.launch { settingsRepository.update { it.copy(chat = transform(it.chat)) } }
    }

    private fun statusToast() {
        val current = _state.value
        val text = listOfNotNull(
            current.serverVersion?.let { "opencode $it" },
            current.effectiveAgent,
            current.model?.key,
            current.directory,
        ).joinToString(" · ")
        _effects.trySend(ChatEffect.Toast(UiText.raw(text.ifBlank { "opencode" })))
    }

    private fun rename(title: String) {
        viewModelScope.launch {
            when (val result = repository.renameSession(sessionId, title.trim())) {
                is ApiResult.Ok -> _state.update { it.copy(title = title.trim()) }
                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    /** The visible conversation as Markdown, for `/export`. */
    fun exportMarkdown(): String = buildString {
        val current = _state.value
        append("# ").append(current.title.ifBlank { "opencode" }).append("\n\n")
        current.visibleMessages.forEach { message ->
            append(if (message.role == Role.USER) "## You\n\n" else "## opencode" + (message.model?.let { " ($it)" } ?: "") + "\n\n")
            message.parts.forEach { part ->
                when (part) {
                    is MessagePart.Text -> append(part.text.trim()).append("\n\n")
                    is MessagePart.Tool -> {
                        append("> **").append(part.call.name).append("**")
                        part.call.title?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
                        append("\n\n")
                    }
                    is MessagePart.File -> append("> attachment: ").append(part.name).append("\n\n")
                    is MessagePart.Notice -> append("_").append(part.text).append("_\n\n")
                    is MessagePart.Reasoning -> Unit
                }
            }
        }
    }.trimEnd() + "\n"

    // ------------------------------------------------------------------ context window

    /** `null` resets the chat to the model's window. */
    fun setSessionContextLimit(tokens: Long?) {
        viewModelScope.launch {
            settingsRepository.update { settings ->
                val limits = settings.sessionContextLimits.toMutableMap()
                if (tokens == null) limits.remove(sessionId) else limits[sessionId] = tokens
                settings.copy(sessionContextLimits = limits)
            }
        }
    }

    /**
     * Per-model window for every chat. With [applyToServer] it is also written to opencode's
     * config (`provider.<p>.models.<m>.limit`) so the server's own auto-compaction agrees.
     */
    fun setModelContextLimit(modelKey: String, tokens: Long?, applyToServer: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { settings ->
                val limits = settings.modelContextLimits.toMutableMap()
                if (tokens == null) limits.remove(modelKey) else limits[modelKey] = tokens
                settings.copy(modelContextLimits = limits)
            }
            if (applyToServer && tokens != null) {
                val ref = ConfigJson.parseModel(modelKey) ?: return@launch
                val output = _state.value.models.firstOrNull { it.key == modelKey }?.outputLimit
                    ?: (tokens / 8).coerceIn(4_096, 64_000)
                when (val result = repository.patchGlobalConfig(ConfigJson.encodeModelLimit(ref, tokens, output.coerceAtMost(tokens)))) {
                    is ApiResult.Ok -> toast(R.string.usage_applied_server)
                    is ApiResult.Err -> _state.update { it.copy(error = result.message) }
                }
            }
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
        if (_state.value.shareUrl == null) {
            toast(R.string.chat_not_shared)
            return
        }
        viewModelScope.launch {
            when (val result = repository.unshare(sessionId)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(shareUrl = null) }
                    toast(R.string.chat_share_revoked)
                }

                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    fun compact() {
        if (_state.value.compacting) return
        _state.update { it.copy(compacting = true, busy = true, turnStartedAt = System.currentTimeMillis()) }
        viewModelScope.launch {
            when (val result = repository.compact(sessionId, model)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(compacting = false) }
                    scheduleReload()
                }

                is ApiResult.Err -> _state.update { it.copy(compacting = false, busy = false, turnStartedAt = null, error = result.message) }
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
            if (result is ApiResult.Ok) _state.update { it.copy(agents = result.value.distinctBy { agent -> agent.name }) }
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

            is OpencodeEvent.QuestionAsked -> {
                if (event.sessionId == sessionId) {
                    _state.update { current ->
                        if (current.questions.any { it.id == event.request.id }) current
                        else current.copy(questions = current.questions + event.request)
                    }
                }
            }

            is OpencodeEvent.QuestionResolved -> {
                if (event.sessionId == sessionId) {
                    _state.update { it.copy(questions = it.questions.filterNot { q -> q.id == event.requestId }) }
                }
            }

            is OpencodeEvent.Status -> {
                if (event.sessionId == sessionId) {
                    _state.update {
                        it.copy(
                            busy = event.busy,
                            retryMessage = event.retryMessage,
                            turnStartedAt = if (event.busy) it.turnStartedAt ?: System.currentTimeMillis() else null,
                        )
                    }
                    if (!event.busy) onTurnFinished()
                }
            }

            is OpencodeEvent.Idle -> {
                if (event.sessionId == sessionId) {
                    _state.update { it.copy(busy = false, retryMessage = null, compacting = false, turnStartedAt = null) }
                    onTurnFinished()
                }
            }

            is OpencodeEvent.MessagesInvalidated -> {
                if (event.sessionId == null || event.sessionId == sessionId) scheduleReload()
            }

            is OpencodeEvent.SessionFailed -> {
                if (event.sessionId == null || event.sessionId == sessionId) {
                    _state.update { it.copy(busy = false, turnStartedAt = null, error = event.message) }
                }
            }

            is OpencodeEvent.SessionsChanged -> loadSessionMeta()

            is OpencodeEvent.McpChanged -> loadCommands()

            is OpencodeEvent.McpAuthUrl -> Unit

            else -> {
                val updated = ChatStreamReducer.reduce(_state.value.messages, event, sessionId)
                if (updated != null) {
                    // Only streaming output proves a turn is running: late message/part updates
                    // arrive after `session.idle` and must not bring the spinner back.
                    if (isLiveOutput(event)) {
                        _state.update { it.copy(messages = updated, busy = true, turnStartedAt = it.turnStartedAt ?: System.currentTimeMillis()) }
                    } else {
                        _state.update { it.copy(messages = updated) }
                    }
                }
            }
        }
    }

    private fun onTurnFinished() {
        scheduleReload()
        viewModelScope.launch {
            // Let the final reload land before the next queued prompt starts a new turn.
            delay(QUEUE_DRAIN_DELAY_MS)
            usageStats?.record(sessionId, _state.value.messages)
            checkBudget()
            if (!maybeAutoCompact()) drainQueue()
        }
    }

    /**
     * Reconciles [ChatUiState.busy] with the server's own session status. Covers missed
     * `session.idle` events and chats opened while a turn is already running elsewhere.
     */
    private suspend fun syncBusy() {
        val serverBusy = (repository.sessionBusy(sessionId) as? ApiResult.Ok)?.value ?: return
        val current = _state.value
        when {
            serverBusy && !current.busy ->
                _state.update { it.copy(busy = true, turnStartedAt = it.turnStartedAt ?: System.currentTimeMillis()) }

            !serverBusy && current.busy && !current.sending && !current.compacting -> {
                val started = current.turnStartedAt ?: 0L
                if (System.currentTimeMillis() - started < BUSY_GRACE_MS) return
                _state.update { it.copy(busy = false, retryMessage = null, turnStartedAt = null) }
                onTurnFinished()
            }
        }
    }

    // ------------------------------------------------------------------ agent questions

    private fun loadQuestions() {
        viewModelScope.launch {
            val pending = (repository.pendingQuestions() as? ApiResult.Ok)?.value ?: return@launch
            val mine = pending.filter { it.sessionId == sessionId }
            _state.update { it.copy(questions = mine) }
        }
    }

    /** [answers] in question order; each is the picked labels plus any typed text. */
    fun answerQuestion(request: QuestionRequest, answers: List<List<String>>) {
        _state.update { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
        viewModelScope.launch {
            when (val result = repository.replyQuestion(sessionId, request.id, answers)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Err -> _state.update { it.copy(error = result.message, questions = it.questions + request) }
            }
        }
    }

    /** Tells the agent the user declined to answer; the tool call fails and the turn goes on. */
    fun dismissQuestion(request: QuestionRequest) {
        _state.update { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
        viewModelScope.launch {
            when (val result = repository.rejectQuestion(sessionId, request.id)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Err -> _state.update { it.copy(error = result.message) }
            }
        }
    }

    // ------------------------------------------------------------------ checkpoints

    /** Loads what reverting to [message] would undo, then shows it for confirmation. */
    fun previewRevert(message: ChatMessage) {
        _state.update { it.copy(revertPreview = RevertPreview(message)) }
        viewModelScope.launch {
            val visible = _state.value.visibleMessages.filterNot { it.id.startsWith("local-") }
            val start = visible.indexOfFirst { it.id == message.id }.coerceAtLeast(0)
            val after = visible.drop(start).filter { it.role == Role.USER }
            // Reverting an answer keeps its prompt but undoes that prompt's edits too.
            val owner = if (message.role == Role.USER) null else visible.take(start).lastOrNull { it.role == Role.USER }
            val users = listOfNotNull(owner) + after
            val unavailable = AtomicBoolean(false)
            val gate = Semaphore(DIFF_PARALLEL)
            val turns = users.map { user ->
                async {
                    gate.withPermit {
                        when (val diff = repository.messageDiff(sessionId, user.id)) {
                            is ApiResult.Ok -> CheckpointTurn(user, diff.value.filter { it.file.isNotBlank() })
                            is ApiResult.Err -> {
                                unavailable.set(true)
                                CheckpointTurn(user, emptyList())
                            }
                        }
                    }
                }
            }.awaitAll().filter { it.files.isNotEmpty() }
            _state.update {
                if (it.revertPreview?.target?.id != message.id) it
                else it.copy(revertPreview = RevertPreview(message, loading = false, turns = turns, unavailable = unavailable.get()))
            }
        }
    }

    fun confirmRevert() {
        val preview = _state.value.revertPreview ?: return
        _state.update { it.copy(revertPreview = null) }
        revertTo(preview.target)
    }

    fun cancelRevert() = _state.update { it.copy(revertPreview = null) }

    /** Client-side auto-compaction at the user's threshold (Settings → Chat). */
    private fun maybeAutoCompact(): Boolean {
        val current = _state.value
        val threshold = current.chat.autoCompactPercent
        if (threshold <= 0 || autoCompacted || current.busy || current.compacting) return false
        val fraction = current.usage?.fraction ?: return false
        if (fraction * 100 < threshold) return false
        autoCompacted = true
        toast(R.string.chat_auto_compacting, threshold)
        compact()
        return true
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
                is ApiResult.Ok -> {
                    _state.update {
                        it.copy(
                            messages = result.value,
                            loading = false,
                            error = null,
                        )
                    }
                    syncBusy()
                    if (_state.value.busy) loadQuestions()
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
        /** `@token` at the start or after whitespace. */
        val MENTION_TOKEN = Regex("""(?:^|\s)@([^\s@]+)""")

        private const val MENTION_DEBOUNCE_MS = 160L
        private const val MAX_MENTION_SUGGESTIONS = 40
        private const val QUEUE_DRAIN_DELAY_MS = 600L

        /** [CommandSuggestion.source] of saved prompts. */
        const val SNIPPET_SOURCE = "snippet"
        private const val MAX_SNIPPET_DEPTH = 3
        private const val BUSY_POLL_MS = 12_000L

        /** A just-sent prompt may not have reached the server's run loop yet. */
        private const val BUSY_GRACE_MS = 4_000L
        private const val DIFF_PARALLEL = 4

        /** Events that only exist while the model is producing output. */
        fun isLiveOutput(event: OpencodeEvent): Boolean = when (event) {
            is OpencodeEvent.TextDelta, is OpencodeEvent.ToolStarted, is OpencodeEvent.ToolCalled -> true
            is OpencodeEvent.PartUpserted -> event.delta != null ||
                (event.part as? MessagePart.Tool)?.call?.status.let { it == ToolStatus.RUNNING || it == ToolStatus.PENDING }
            else -> false
        }

        fun factory(container: AppContainer, sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    repository = container.sessionRepository,
                    connection = container.connectionManager,
                    settingsRepository = container.settingsRepository,
                    sessionId = sessionId,
                    hooks = container.hookRunner,
                    workbench = container.workbench,
                    usageStats = container.usageStats,
                    tasks = container.backgroundTasks,
                    drafts = container.draftInbox,
                )
            }
        }
    }
}
