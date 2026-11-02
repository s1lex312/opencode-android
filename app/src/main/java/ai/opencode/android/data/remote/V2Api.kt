package ai.opencode.android.data.remote

import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ServerInfo
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.model.ToolCall
import ai.opencode.android.data.model.ToolStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client for the opencode 2.x `HttpApi` surface.
 *
 * Envelopes: most endpoints answer `{ "data": ... }` (sometimes with a `location` sibling),
 * session messages are projections whose `content` array carries text/reasoning/tool parts, and
 * SSE events are `{id, created, type, data}` objects.
 */
class V2Api(override val http: ServerHttp) : OpencodeApi {

    override val flavor: ApiFlavor = ApiFlavor.V2
    override val baseUrl: String get() = http.baseUrl
    override val eventPath: String = "/api/event"

    override suspend fun probe(): ServerInfo {
        val response = http.getJson("/api/info")
        val obj = (response as? JsonObject)?.let { it.obj("data") ?: it }
        return ServerInfo(
            flavor = ApiFlavor.V2,
            version = obj?.str("version"),
            directory = obj?.str("paths", "cwd"),
        )
    }

    override suspend fun listSessions(): List<ServerSession> {
        val response = http.getJson(
            "/api/session",
            mapOf("limit" to SESSION_LIMIT.toString(), "order" to "desc"),
        )
        val root = response as? JsonObject ?: return emptyList()
        val array = (root.obj("data") as? JsonArray) ?: (response as? JsonArray) ?: return emptyList()
        return array.objects().mapNotNull(::toSession)
    }

    override suspend fun createSession(title: String?): ServerSession {
        val body = buildJsonObject { if (!title.isNullOrBlank()) put("title", title) }
        val response = http.postJson("/api/session", body)
        val root = response as? JsonObject ?: throw ApiException(null, "Malformed response")
        val obj = root.obj("data") ?: root
        return toSession(obj) ?: throw ApiException(null, "Malformed session in response")
    }

    override suspend fun deleteSession(sessionId: String) {
        http.deleteJson("/api/session/${enc(sessionId)}")
    }

    override suspend fun messages(sessionId: String): List<ChatMessage> {
        val response = http.getJson(
            "/api/session/${enc(sessionId)}/message",
            mapOf("limit" to MESSAGE_LIMIT.toString(), "order" to "asc"),
        )
        val array = (response as? JsonObject)?.obj("data") as? JsonArray
            ?: response as? JsonArray
            ?: return emptyList()
        return array.objects().mapNotNull(::toMessage)
    }

    override suspend fun sendPrompt(sessionId: String, text: String, model: ModelRef?, agent: String?) {
        // v2 keeps model/agent on the session, so select them before admitting the prompt.
        if (model != null && model.providerID.isNotBlank() && model.modelID.isNotBlank()) {
            runCatching {
                http.postJson(
                    "/api/session/${enc(sessionId)}/model",
                    buildJsonObject {
                        put(
                            "model",
                            buildJsonObject {
                                put("id", model.modelID)
                                put("providerID", model.providerID)
                            },
                        )
                    },
                )
            }
        }
        if (!agent.isNullOrBlank()) {
            runCatching {
                http.postJson("/api/session/${enc(sessionId)}/agent", buildJsonObject { put("agent", agent) })
            }
        }
        http.postJson(
            "/api/session/${enc(sessionId)}/prompt",
            buildJsonObject { put("text", text) },
        )
    }

    override suspend fun abort(sessionId: String) {
        http.postJson("/api/session/${enc(sessionId)}/interrupt")
    }

    override suspend fun diff(sessionId: String): List<FileDiffPayload> {
        val response = http.getJson("/api/session/${enc(sessionId)}/diff")
        val array = (response as? JsonObject)?.obj("data") as? JsonArray
            ?: response as? JsonArray
            ?: return emptyList()
        return array.objects().map { obj ->
            FileDiffPayload(
                file = obj.str("file").orEmpty(),
                patch = obj.str("patch").orEmpty(),
                additions = obj.int("additions"),
                deletions = obj.int("deletions"),
                status = obj.str("status"),
            )
        }
    }

    override suspend fun models(): List<ModelOption> {
        val response = http.getJson("/api/model")
        val array = (response as? JsonObject)?.obj("data") as? JsonArray
            ?: response as? JsonArray
            ?: return emptyList()
        return array.objects().mapNotNull { obj ->
            val providerID = obj.str("providerID") ?: return@mapNotNull null
            val modelID = obj.str("modelID") ?: obj.str("id") ?: return@mapNotNull null
            ModelOption(
                providerID = providerID,
                modelID = modelID,
                name = obj.str("name") ?: modelID,
                disabled = obj.bool("enabled") == false,
            )
        }
    }

    override suspend fun replyPermission(sessionId: String, permissionId: String, decision: PermissionDecision) {
        http.postJson(
            "/api/session/${enc(sessionId)}/permission/${enc(permissionId)}/reply",
            buildJsonObject { put("decision", decision.wire) },
        )
    }

    // ------------------------------------------------------------------ mapping

    private fun toSession(obj: JsonObject): ServerSession? {
        val id = obj.str("id") ?: return null
        val busy = obj.long("time", "busy")
        val idle = obj.long("time", "idle")
        return ServerSession(
            id = id,
            title = obj.str("title") ?: obj.str("slug") ?: "Untitled session",
            directory = obj.str("location", "directory") ?: obj.str("directory"),
            created = obj.long("time", "created") ?: 0L,
            updated = obj.long("time", "updated") ?: obj.long("time", "created") ?: 0L,
            parentID = obj.str("parentID"),
            busy = busy != null && (idle == null || busy > idle),
        )
    }

    private fun toMessage(obj: JsonObject): ChatMessage? {
        val id = obj.str("id") ?: return null
        val type = obj.str("type") ?: return null
        val created = obj.long("time", "created") ?: 0L
        val model = obj.obj("model")?.str("id") ?: obj.str("modelID")
        return when (type) {
            "user" -> ChatMessage(
                id = id,
                role = Role.USER,
                created = created,
                parts = listOf(MessagePart.Text("$id:user", obj.str("text").orEmpty())),
                model = model,
            )

            "assistant" -> {
                val parts = obj.arr("content")?.objects()
                    ?.mapIndexedNotNull { index, part -> toPart(id, index, part) }
                    .orEmpty()
                ChatMessage(
                    id = id,
                    role = Role.ASSISTANT,
                    created = created,
                    parts = parts,
                    error = obj.obj("error")?.str("message"),
                    model = model,
                )
            }

            "idle" -> ChatMessage(
                id = id,
                role = Role.ASSISTANT,
                created = created,
                parts = listOf(
                    MessagePart.Notice(
                        "$id:idle",
                        "Turn finished" + (obj.str("outcome")?.let { " · $it" } ?: ""),
                    ),
                ),
                error = obj.obj("error")?.str("message"),
            )

            "shell" -> ChatMessage(
                id = id,
                role = Role.ASSISTANT,
                created = created,
                parts = listOf(
                    MessagePart.Tool(
                        "$id:shell",
                        ToolCall(
                            id = obj.str("shellID") ?: id,
                            name = "shell",
                            status = when (obj.str("status")) {
                                "running" -> ToolStatus.RUNNING
                                "exited" -> ToolStatus.COMPLETED
                                else -> ToolStatus.ERROR
                            },
                            input = obj.str("command"),
                            output = obj.str("output"),
                        ),
                    ),
                ),
            )

            "compaction" -> ChatMessage(
                id = id,
                role = Role.ASSISTANT,
                created = created,
                parts = listOf(MessagePart.Notice("$id:compaction", "Conversation compacted")),
            )

            "system" -> ChatMessage(
                id = id,
                role = Role.ASSISTANT,
                created = created,
                parts = listOf(
                    MessagePart.Notice("$id:system", obj.str("description") ?: obj.str("text").orEmpty()),
                ),
            )

            // agent-switched / model-switched / location-switched / skill / synthetic are not
            // rendered in the mobile transcript.
            else -> null
        }
    }

    private fun toPart(messageId: String, index: Int, part: JsonObject): MessagePart? {
        val partId = "$messageId:part-$index"
        return when (part.str("type")) {
            "text" -> part.str("text")?.let { MessagePart.Text(partId, it) }
            "reasoning" -> part.str("text")?.let { MessagePart.Reasoning(partId, it) }
            "tool" -> {
                val state = part.obj("state")
                val status = when (state?.str("status")) {
                    "streaming", "pending" -> ToolStatus.PENDING
                    "running" -> ToolStatus.RUNNING
                    "completed" -> ToolStatus.COMPLETED
                    "error" -> ToolStatus.ERROR
                    else -> ToolStatus.RUNNING
                }
                val call = ToolCall(
                    id = part.str("id") ?: partId,
                    name = part.str("name") ?: "tool",
                    status = status,
                    input = state?.obj("input").prettyOrEmpty(),
                    output = state?.arr("content")?.toolContentText()?.takeIf { it.isNotBlank() },
                    title = state?.obj("metadata")?.str("title"),
                    error = state?.obj("error")?.str("message"),
                )
                MessagePart.Tool(partId, call)
            }

            else -> null
        }
    }

    private companion object {
        const val SESSION_LIMIT = 100
        const val MESSAGE_LIMIT = 200

        fun enc(pathSegment: String): String =
            java.net.URLEncoder.encode(pathSegment, "UTF-8").replace("+", "%20")
    }
}
