package ai.opencode.android.data.model

/**
 * A session as exposed by the opencode server. Both the v2 (`/api/session`) and the
 * legacy (`/session`) HTTP APIs are normalised into this shape.
 */
data class ServerSession(
    val id: String,
    val title: String,
    val directory: String? = null,
    val created: Long = 0L,
    val updated: Long = 0L,
    val parentID: String? = null,
    val busy: Boolean = false,
    /** Public link when the session is shared (`/session/{id}/share`). */
    val shareUrl: String? = null,
    /** Messages from this id on are reverted (hidden until the next prompt or an unrevert). */
    val revertMessageId: String? = null,
)

enum class Role { USER, ASSISTANT }

enum class ToolStatus { PENDING, RUNNING, COMPLETED, ERROR }

/**
 * A single tool invocation surfaced as a card in the chat transcript.
 * [input] and [output] are already stringified (pretty-printed JSON / text) and truncated
 * for rendering by the UI layer.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val status: ToolStatus = ToolStatus.RUNNING,
    val input: String? = null,
    val output: String? = null,
    val title: String? = null,
    val error: String? = null,
)

enum class NoticeKind { INFO, WARNING, ERROR }

sealed interface MessagePart {
    val id: String

    data class Text(override val id: String, val text: String) : MessagePart

    data class Reasoning(override val id: String, val text: String) : MessagePart

    data class Tool(override val id: String, val call: ToolCall) : MessagePart

    data class Notice(override val id: String, val text: String, val kind: NoticeKind = NoticeKind.INFO) : MessagePart

    /** A file or image attached to a prompt. */
    data class File(override val id: String, val name: String, val mime: String) : MessagePart
}

/** Token accounting of one assistant turn, as reported by the server. */
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    /** Tokens the turn occupied in the context window (what the next turn starts from). */
    val contextTokens: Long get() = input + output + reasoning + cacheRead + cacheWrite

    val isEmpty: Boolean get() = contextTokens == 0L
}

data class ChatMessage(
    val id: String,
    val role: Role,
    val created: Long = 0L,
    val parts: List<MessagePart> = emptyList(),
    val error: String? = null,
    val model: String? = null,
    val providerId: String? = null,
    val tokens: TokenUsage? = null,
    /** USD spent on this turn. */
    val cost: Double? = null,
) {
    val plainText: String
        get() = parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }
}

/** A pending tool-approval request raised by the agent. */
data class PermissionRequest(
    val id: String,
    val sessionId: String,
    val title: String,
    val action: String,
    val resources: List<String> = emptyList(),
    val metadataJson: String? = null,
) 

enum class PermissionDecision(val wire: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}

enum class PermissionPolicy {
    /** Show a card in chat and wait for the user. */
    ASK,

    /** Automatically answer "once" without showing a card. */
    AUTO_ALLOW,

    /** Automatically reject every request. */
    DENY,
}

/** Minimal model reference used for prompt requests. */
data class ModelRef(val providerID: String, val modelID: String) {
    val key: String get() = "$providerID/$modelID"

    val displayName: String get() = if (modelID.isBlank()) providerID else modelID
}

/** An entry of the server's model catalogue (v2 `/api/model` or legacy `/config/providers`). */
data class ModelOption(
    val providerID: String,
    val modelID: String,
    val name: String,
    val disabled: Boolean = false,
    /** Context window in tokens, when the catalogue knows it. */
    val contextLimit: Long? = null,
    /** Maximum output tokens, when the catalogue knows it. */
    val outputLimit: Long? = null,
) {
    val key: String get() = "$providerID/$modelID"
    val label: String get() = if (name.isBlank() || name == modelID) modelID else "$name · $modelID"
}

/** A selectable agent (`build`, `plan`, custom ones) as listed by the server. */
data class AgentOption(
    val name: String,
    val description: String? = null,
    /** `primary`, `subagent` or `all`; only primary/all agents can drive a conversation. */
    val mode: String? = null,
) {
    val selectable: Boolean get() = mode == null || mode == "primary" || mode == "all"
}

enum class ApiFlavor {
    /** opencode 2.x `HttpApi`: `/api/...` paths, `{data: ...}` envelopes, `data`-carrying events. */
    V2,

    /** opencode 0.x/1.x REST surface: `/session`, `/event`, `{properties: ...}` events. */
    LEGACY,
}

data class ServerInfo(
    val flavor: ApiFlavor,
    val version: String? = null,
    val directory: String? = null,
)

/** One file entry returned by `/session/{id}/diff` (both API flavours). */
data class FileDiffPayload(
    val file: String,
    val patch: String,
    val additions: Int? = null,
    val deletions: Int? = null,
    val status: String? = null,
)

/** Connection lifecycle exposed to the UI. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val info: ServerInfo, val baseUrl: String) : ConnectionState
    data class Reconnecting(val attempt: Int, val reason: String) : ConnectionState
    data class Failed(val message: String, val hint: String? = null) : ConnectionState
}

/** A server-side slash command (`/command`, custom commands, MCP prompts, skills). */
data class SlashCommand(
    val name: String,
    val description: String? = null,
    val source: String? = null,
    /** Argument placeholders of the template (`$1`, `$ARGUMENTS`). */
    val hints: List<String> = emptyList(),
)

/** A file or image the user attached to the next prompt, already encoded as a `data:` URL. */
data class PromptAttachment(
    val id: String,
    val name: String,
    val mime: String,
    val dataUrl: String,
    val sizeBytes: Long,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

/** Everything one prompt carries besides the session. */
data class PromptRequest(
    val text: String,
    val model: ModelRef? = null,
    val agent: String? = null,
    /** Extra system prompt (user-defined profile); `null` keeps the agent's own prompt only. */
    val system: String? = null,
    val attachments: List<PromptAttachment> = emptyList(),
    /** Absolute paths of `@`-mentioned project files. */
    val mentionedFiles: List<String> = emptyList(),
    /** `@`-mentioned subagents the primary agent should delegate to. */
    val mentionedAgents: List<String> = emptyList(),
)

/** A pseudo-terminal opened on the server. */
data class PtyInfo(val id: String, val cwd: String? = null)

/** An entry of the `@` popup: a project file/directory or a subagent. */
data class MentionCandidate(
    val value: String,
    val kind: Kind,
    val description: String? = null,
) {
    enum class Kind { FILE, DIRECTORY, AGENT }
}

/** How an MCP server is launched or reached; mirrors opencode's `mcp.<name>` config entry. */
sealed interface McpConfig {
    val enabled: Boolean
    val timeoutMs: Int?

    data class Local(
        val command: List<String>,
        val environment: Map<String, String> = emptyMap(),
        val cwd: String? = null,
        override val enabled: Boolean = true,
        override val timeoutMs: Int? = null,
    ) : McpConfig

    data class Remote(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        /** `false` turns off OAuth auto-detection; `true` lets opencode negotiate it. */
        val oauth: Boolean = true,
        override val enabled: Boolean = true,
        override val timeoutMs: Int? = null,
    ) : McpConfig
}

/** Live state of one MCP server as reported by `GET /mcp`. */
data class McpServerInfo(
    val name: String,
    val status: Status,
    val error: String? = null,
    /** Config from the global opencode config, when this server is defined there. */
    val config: McpConfig? = null,
) {
    enum class Status { CONNECTED, PENDING, DISABLED, FAILED, NEEDS_AUTH, NEEDS_REGISTRATION, UNKNOWN }
}

/** Permission answer configured in opencode (`ask` / `allow` / `deny`). */
enum class PermissionAction(val wire: String) {
    ASK("ask"),
    ALLOW("allow"),
    DENY("deny"),
    ;

    companion object {
        fun of(wire: String?): PermissionAction? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * An agent with everything the editor can change. Values come from `GET /agent` (effective) and
 * the user's overrides in the global config (`agent.<name>`).
 */
data class AgentDetail(
    val name: String,
    val description: String? = null,
    val mode: String = "all",
    val native: Boolean = false,
    val hidden: Boolean = false,
    val disabled: Boolean = false,
    val model: ModelRef? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val steps: Int? = null,
    val prompt: String? = null,
    val color: String? = null,
    /** Tool id → enabled; absent tools inherit. */
    val tools: Map<String, Boolean> = emptyMap(),
    /** Permission key (`edit`, `bash`, ...) → action; absent keys inherit. */
    val permissions: Map<String, PermissionAction> = emptyMap(),
) {
    val isSubagent: Boolean get() = mode == "subagent"
}

/** The parts of the global opencode config the "Agent behaviour" screen edits. */
data class BehaviourConfig(
    val permissions: Map<String, PermissionAction> = emptyMap(),
    val tools: Map<String, Boolean> = emptyMap(),
    val smallModel: String? = null,
    val defaultAgent: String? = null,
    val autoCompact: Boolean? = null,
    val prune: Boolean? = null,
    val reservedTokens: Int? = null,
    val snapshot: Boolean? = null,
    val share: String? = null,
    val instructions: List<String> = emptyList(),
    val subagentDepth: Int? = null,
    val username: String? = null,
)

/** One choice of an agent question (the `question` tool). */
data class QuestionOption(val label: String, val description: String = "")

data class QuestionItem(
    val question: String,
    /** Very short label, shown as a chip. */
    val header: String = "",
    val options: List<QuestionOption> = emptyList(),
    /** Several options may be picked. */
    val multiple: Boolean = false,
    /** A free-text answer is allowed besides the options (opencode's default). */
    val custom: Boolean = true,
)

/** The agent is blocked until these questions are answered or dismissed. */
data class QuestionRequest(
    val id: String,
    val sessionId: String,
    val questions: List<QuestionItem>,
    val callId: String? = null,
)
