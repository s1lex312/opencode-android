package ai.opencode.android.data.events

import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.PermissionRequest
import ai.opencode.android.data.model.QuestionItem
import ai.opencode.android.data.model.QuestionOption
import ai.opencode.android.data.model.QuestionRequest
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ToolCall
import ai.opencode.android.data.model.ToolStatus
import ai.opencode.android.data.remote.arr
import ai.opencode.android.data.remote.bool
import ai.opencode.android.data.remote.double
import ai.opencode.android.data.remote.toTokenUsage
import ai.opencode.android.data.remote.int
import ai.opencode.android.data.remote.long
import ai.opencode.android.data.remote.obj
import ai.opencode.android.data.remote.objects
import ai.opencode.android.data.remote.prettyOrEmpty
import ai.opencode.android.data.remote.str
import ai.opencode.android.data.remote.strList
import ai.opencode.android.data.remote.toolContentText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Translates raw server event JSON into [OpencodeEvent].
 *
 * Both flavours are handled here (and covered by unit tests with fixtures captured from the
 * respective server versions), which keeps the rest of the app free of wire-format branching.
 */
object EventMapper {

    fun map(flavor: ApiFlavor, raw: JsonObject): OpencodeEvent {
        val event = unwrapGlobal(raw)
        return when (flavor) {
            ApiFlavor.V2 -> mapV2(event)
            ApiFlavor.LEGACY -> mapLegacy(event)
        }
    }

    /** `/global/event` wraps payloads as `{directory, payload}`; normalise that away. */
    private fun unwrapGlobal(raw: JsonObject): JsonObject {
        val payload = raw.obj("payload")
        return if (payload != null && payload["type"] != null) payload else raw
    }

    // ------------------------------------------------------------------ v2

    private fun mapV2(raw: JsonObject): OpencodeEvent {
        val type = raw.str("type") ?: return OpencodeEvent.Ignored
        val data = raw.obj("data") ?: JsonObject(emptyMap())
        val sessionId = data.str("sessionID")

        return when (type) {
            "session.text.delta" -> {
                val messageId = data.str("assistantMessageID")
                val delta = data.str("delta")
                if (sessionId == null || messageId == null || delta == null) {
                    OpencodeEvent.Ignored
                } else {
                    OpencodeEvent.TextDelta(sessionId, messageId, data.int("ordinal") ?: 0, delta)
                }
            }

            "session.text.ended" -> {
                val messageId = data.str("assistantMessageID")
                if (sessionId == null || messageId == null) {
                    OpencodeEvent.Ignored
                } else {
                    OpencodeEvent.TextCompleted(
                        sessionId = sessionId,
                        messageId = messageId,
                        ordinal = data.int("ordinal") ?: 0,
                        text = data.str("text").orEmpty(),
                    )
                }
            }

            "session.tool.input.started" -> {
                val messageId = data.str("assistantMessageID")
                val toolId = data.str("id")
                if (sessionId == null || messageId == null || toolId == null) {
                    OpencodeEvent.Ignored
                } else {
                    OpencodeEvent.ToolStarted(sessionId, messageId, toolId, data.str("name") ?: "tool")
                }
            }

            "session.tool.called" -> {
                val messageId = data.str("assistantMessageID")
                val toolId = data.str("id")
                if (sessionId == null || messageId == null || toolId == null) {
                    OpencodeEvent.Ignored
                } else {
                    OpencodeEvent.ToolCalled(sessionId, messageId, toolId, data.obj("input").prettyOrEmpty())
                }
            }

            "session.tool.success" -> {
                val messageId = data.str("assistantMessageID")
                val toolId = data.str("id")
                if (sessionId == null || messageId == null || toolId == null) {
                    OpencodeEvent.Ignored
                } else {
                    val output = data.arr("content")?.toolContentText()?.takeIf { it.isNotBlank() }
                    OpencodeEvent.ToolSucceeded(
                        sessionId = sessionId,
                        messageId = messageId,
                        toolId = toolId,
                        output = output,
                        title = data.obj("metadata")?.str("title"),
                    )
                }
            }

            "session.tool.failed" -> {
                val messageId = data.str("assistantMessageID")
                val toolId = data.str("id")
                if (sessionId == null || messageId == null || toolId == null) {
                    OpencodeEvent.Ignored
                } else {
                    val error = data.obj("error")?.str("message")
                        ?: data.obj("error")?.toString()
                        ?: data.arr("content")?.toolContentText()
                        ?: "Tool failed"
                    OpencodeEvent.ToolFailed(sessionId, messageId, toolId, error)
                }
            }

            "session.status" -> {
                if (sessionId == null) {
                    OpencodeEvent.Ignored
                } else {
                    val status = data.obj("status")
                    val statusType = status?.str("type")
                    OpencodeEvent.Status(
                        sessionId = sessionId,
                        busy = statusType != null && statusType != "idle",
                        retryMessage = status?.str("message"),
                    )
                }
            }

            "session.execution.started" -> {
                if (sessionId == null) OpencodeEvent.Ignored
                else OpencodeEvent.Status(sessionId, busy = true)
            }

            "session.execution.succeeded", "session.execution.interrupted",
            "session.step.ended", "session.compaction.ended" -> {
                if (sessionId == null) OpencodeEvent.Ignored
                else OpencodeEvent.MessagesInvalidated(sessionId)
            }

            "session.execution.failed" -> {
                val message = data.obj("error")?.str("message") ?: "Agent execution failed"
                OpencodeEvent.SessionFailed(sessionId, message)
            }

            "session.idle" -> {
                if (sessionId == null) OpencodeEvent.Ignored else OpencodeEvent.Idle(sessionId)
            }

            "permission.asked" -> {
                val id = data.str("id")
                val permissionSession = data.str("sessionID")
                if (id == null || permissionSession == null) {
                    OpencodeEvent.Ignored
                } else {
                    OpencodeEvent.PermissionAsked(
                        PermissionRequest(
                            id = id,
                            sessionId = permissionSession,
                            title = data.str("action") ?: data.str("message") ?: "Permission required",
                            action = data.str("action") ?: "unknown",
                            resources = data.strList("resources"),
                            metadataJson = data.obj("metadata").prettyOrEmpty(),
                        ),
                    )
                }
            }

            "permission.replied" -> {
                val permissionId = data.str("id") ?: data.str("requestID")
                if (sessionId == null || permissionId == null) {
                    OpencodeEvent.Ignored
                } else {
                    OpencodeEvent.PermissionResolved(sessionId, permissionId)
                }
            }

            "question.asked", "question.v2.asked" -> question(data)?.let { OpencodeEvent.QuestionAsked(it) } ?: OpencodeEvent.Ignored

            "question.replied", "question.rejected", "question.v2.replied", "question.v2.rejected" -> {
                val requestId = data.str("requestID") ?: data.str("id")
                if (sessionId == null || requestId == null) OpencodeEvent.Ignored else OpencodeEvent.QuestionResolved(sessionId, requestId)
            }

            "session.created", "session.renamed", "session.deleted", "session.archived",
            "session.unarchived", "session.moved", "session.metadata.updated", "session.forked",
            -> OpencodeEvent.SessionsChanged

            "mcp.browser.open.failed" -> mcpAuthUrl(data)

            "mcp.tools.changed", "mcp.status.changed" -> OpencodeEvent.McpChanged

            "session.revert.staged", "session.revert.cleared", "session.revert.committed" -> {
                if (sessionId == null) OpencodeEvent.Ignored else OpencodeEvent.MessagesInvalidated(sessionId)
            }

            "filesystem.changed" -> {
                if (sessionId == null) OpencodeEvent.Ignored else OpencodeEvent.DiffChanged(sessionId)
            }

            else -> OpencodeEvent.Ignored
        }
    }

    // ------------------------------------------------------------------ legacy

    private fun mapLegacy(raw: JsonObject): OpencodeEvent {
        val type = raw.str("type") ?: return OpencodeEvent.Ignored
        val props = raw.obj("properties") ?: JsonObject(emptyMap())

        return when (type) {
            "message.updated" -> {
                val info = props.obj("info") ?: return OpencodeEvent.Ignored
                val sessionId = info.str("sessionID") ?: return OpencodeEvent.Ignored
                val id = info.str("id") ?: return OpencodeEvent.Ignored
                val role = if (info.str("role") == "user") Role.USER else Role.ASSISTANT
                val error = info.obj("error")?.let { errorText(it) }
                OpencodeEvent.MessageUpserted(
                    sessionId = sessionId,
                    message = ChatMessage(
                        id = id,
                        role = role,
                        created = info.long("time", "created") ?: 0L,
                        error = error,
                        model = info.str("modelID"),
                        providerId = info.str("providerID"),
                        tokens = info.obj("tokens").toTokenUsage(),
                        cost = info.double("cost"),
                    ),
                )
            }

            "message.part.updated" -> {
                val part = props.obj("part") ?: return OpencodeEvent.Ignored
                val sessionId = part.str("sessionID") ?: return OpencodeEvent.Ignored
                val messageId = part.str("messageID") ?: return OpencodeEvent.Ignored
                val mapped = legacyPart(part) ?: return OpencodeEvent.Ignored
                OpencodeEvent.PartUpserted(sessionId, messageId, mapped, props.str("delta"))
            }

            "message.part.removed" -> {
                val sessionId = props.str("sessionID") ?: return OpencodeEvent.Ignored
                val messageId = props.str("messageID") ?: return OpencodeEvent.Ignored
                val partId = props.str("partID") ?: return OpencodeEvent.Ignored
                OpencodeEvent.PartRemoved(sessionId, messageId, partId)
            }

            "message.removed" -> {
                val sessionId = props.str("sessionID") ?: return OpencodeEvent.Ignored
                val messageId = props.str("messageID") ?: return OpencodeEvent.Ignored
                OpencodeEvent.MessageRemoved(sessionId, messageId)
            }

            "session.idle" -> {
                val sessionId = props.str("sessionID") ?: return OpencodeEvent.Ignored
                OpencodeEvent.Idle(sessionId)
            }

            "session.status" -> {
                val sessionId = props.str("sessionID") ?: return OpencodeEvent.Ignored
                val status = props.obj("status")
                OpencodeEvent.Status(
                    sessionId = sessionId,
                    busy = status?.str("type") != "idle",
                    retryMessage = status?.str("message"),
                )
            }

            "session.error" -> {
                val error = props.obj("error")?.let { errorText(it) } ?: "Session error"
                OpencodeEvent.SessionFailed(props.str("sessionID"), error)
            }

            "permission.asked" -> {
                val id = props.str("id") ?: return OpencodeEvent.Ignored
                val sessionId = props.str("sessionID") ?: return OpencodeEvent.Ignored
                val permission = props.str("permission") ?: "permission"
                val patterns = props.strList("patterns")
                OpencodeEvent.PermissionAsked(
                    PermissionRequest(
                        id = id,
                        sessionId = sessionId,
                        title = props.obj("metadata")?.str("description")
                            ?: patterns.firstOrNull()?.let { "$permission: $it" }
                            ?: permission,
                        action = permission,
                        resources = patterns,
                        metadataJson = props.obj("metadata").prettyOrEmpty(),
                    ),
                )
            }

            "permission.updated" -> {
                val id = props.str("id") ?: return OpencodeEvent.Ignored
                val sessionId = props.str("sessionID") ?: return OpencodeEvent.Ignored
                OpencodeEvent.PermissionAsked(
                    PermissionRequest(
                        id = id,
                        sessionId = sessionId,
                        title = props.str("title") ?: props.str("type") ?: "Permission required",
                        action = props.str("type") ?: "unknown",
                        resources = props.strList("pattern"),
                        metadataJson = props.obj("metadata").prettyOrEmpty(),
                    ),
                )
            }

            "permission.replied" -> {
                val sessionId = props.str("sessionID") ?: return OpencodeEvent.Ignored
                val permissionId = props.str("permissionID") ?: props.str("requestID") ?: return OpencodeEvent.Ignored
                OpencodeEvent.PermissionResolved(sessionId, permissionId)
            }

            "question.asked" -> question(props)?.let { OpencodeEvent.QuestionAsked(it) } ?: OpencodeEvent.Ignored

            "question.replied", "question.rejected" -> {
                val sessionId = props.str("sessionID") ?: return OpencodeEvent.Ignored
                val requestId = props.str("requestID") ?: props.str("id") ?: return OpencodeEvent.Ignored
                OpencodeEvent.QuestionResolved(sessionId, requestId)
            }

            "session.created", "session.updated", "session.deleted" -> OpencodeEvent.SessionsChanged

            "session.diff" -> {
                val sessionId = props.str("sessionID") ?: return OpencodeEvent.Ignored
                OpencodeEvent.DiffChanged(sessionId, mapsToDiffs(props.arr("diff")))
            }

            "server.connected", "todo.updated", "file.edited", "vcs.branch.updated" -> OpencodeEvent.Ignored

            "mcp.browser.open.failed" -> mcpAuthUrl(props)

            "mcp.tools.changed" -> OpencodeEvent.McpChanged

            else -> OpencodeEvent.Ignored
        }
    }

    /** A `QuestionRequest` object, from events and from `GET /question`. */
    fun question(data: JsonObject): QuestionRequest? {
        val id = data.str("id") ?: return null
        val sessionId = data.str("sessionID") ?: return null
        val questions = data.arr("questions")?.objects()?.mapNotNull { item ->
            val text = item.str("question") ?: return@mapNotNull null
            QuestionItem(
                question = text,
                header = item.str("header").orEmpty(),
                options = item.arr("options")?.objects()?.mapNotNull { option ->
                    option.str("label")?.let { QuestionOption(it, option.str("description").orEmpty()) }
                }.orEmpty(),
                multiple = item.bool("multiple") ?: false,
                custom = item.bool("custom") ?: true,
            )
        }.orEmpty()
        if (questions.isEmpty()) return null
        return QuestionRequest(id, sessionId, questions, data.str("tool", "callID"))
    }

    private fun mcpAuthUrl(data: JsonObject): OpencodeEvent {
        val url = data.str("url") ?: return OpencodeEvent.Ignored
        return OpencodeEvent.McpAuthUrl(data.str("mcpName") ?: data.str("name").orEmpty(), url)
    }

    /** Maps one legacy (0.x/1.x) `Part` object; shared with the REST message loader. */
    fun legacyPart(part: JsonObject): MessagePart? {
        val id = part.str("id") ?: return null
        return when (part.str("type")) {
            "text" -> MessagePart.Text(id, part.str("text").orEmpty())
            "reasoning" -> MessagePart.Reasoning(id, part.str("text").orEmpty())
            "tool" -> {
                val state = part.obj("state")
                val status = when (state?.str("status")) {
                    "pending" -> ToolStatus.PENDING
                    "running" -> ToolStatus.RUNNING
                    "completed" -> ToolStatus.COMPLETED
                    "error" -> ToolStatus.ERROR
                    else -> ToolStatus.RUNNING
                }
                val call = ToolCall(
                    id = part.str("callID") ?: id,
                    name = part.str("tool") ?: "tool",
                    status = status,
                    input = state?.obj("input").prettyOrEmpty(),
                    output = state?.str("output") ?: state?.arr("content")?.toolContentText(),
                    title = state?.str("title"),
                    error = state?.str("error"),
                )
                MessagePart.Tool(id, call)
            }

            "file" -> MessagePart.File(
                id = id,
                name = part.str("filename") ?: part.str("source", "path")?.substringAfterLast('/') ?: "file",
                mime = part.str("mime") ?: "application/octet-stream",
            )

            "retry", "subtask", "compaction" -> MessagePart.Notice(
                id,
                part.str("prompt") ?: part.str("description") ?: part.str("type").orEmpty(),
            )

            else -> null
        }
    }

    private fun mapsToDiffs(raw: kotlinx.serialization.json.JsonArray?): List<FileDiffPayload>? {
        raw ?: return null
        return raw.objects().map { obj ->
            FileDiffPayload(
                file = obj.str("file").orEmpty(),
                patch = obj.str("patch").orEmpty(),
                additions = obj.int("additions"),
                deletions = obj.int("deletions"),
                status = obj.str("status"),
            )
        }
    }

    private fun errorText(error: JsonObject): String =
        error.str("data", "message")
            ?: error.str("message")
            ?: error.str("name")
            ?: "Unknown error"

    /** Convenience for tests/fixtures. */
    fun typeOf(raw: JsonObject): String? = unwrapGlobal(raw).str("type")

    @Suppress("unused")
    private fun JsonElement.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}
