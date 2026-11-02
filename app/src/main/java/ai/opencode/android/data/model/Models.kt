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
}

data class ChatMessage(
    val id: String,
    val role: Role,
    val created: Long = 0L,
    val parts: List<MessagePart> = emptyList(),
    val error: String? = null,
    val model: String? = null,
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
