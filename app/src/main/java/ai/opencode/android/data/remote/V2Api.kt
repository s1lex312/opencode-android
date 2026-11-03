package ai.opencode.android.data.remote

import ai.opencode.android.data.model.AgentDetail
import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.McpConfig
import ai.opencode.android.data.model.McpServerInfo
import ai.opencode.android.data.model.MentionCandidate
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PromptRequest
import ai.opencode.android.data.model.PtyInfo
import ai.opencode.android.data.model.QuestionRequest
import ai.opencode.android.data.events.EventMapper
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ServerInfo
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.model.SlashCommand
import kotlinx.serialization.json.buildJsonArray
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
        val array = root.arr("data") ?: (response as? JsonArray) ?: return emptyList()
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
        val array = (response as? JsonObject)?.arr("data")
            ?: response as? JsonArray
            ?: return emptyList()
        return array.objects().mapNotNull(::toMessage)
    }

    override suspend fun session(sessionId: String): ServerSession {
        val response = http.getJson("/api/session/${enc(sessionId)}") as? JsonObject
            ?: throw ApiException(null, "Malformed session response")
        return toSession(response.obj("data") ?: response) ?: throw ApiException(null, "Session response has no id")
    }

    override suspend fun sendPrompt(sessionId: String, prompt: PromptRequest) {
        selectModelAndAgent(sessionId, prompt.model, prompt.agent)
        applySystemPrompt(sessionId, prompt.system)
        http.postJson(
            "/api/session/${enc(sessionId)}/prompt",
            buildJsonObject {
                put("text", prompt.text)
                val files = filesJson(prompt)
                if (files.isNotEmpty()) put("files", files)
            },
        )
    }

    private suspend fun selectModelAndAgent(sessionId: String, model: ModelRef?, agent: String?) {
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
    }

    /**
     * 2.x has no per-prompt `system` field; durable session instruction entries are the
     * equivalent. Experimental on the server, hence best effort.
     */
    private suspend fun applySystemPrompt(sessionId: String, system: String?) {
        val path = "/api/experimental/session/${enc(sessionId)}/instructions/entries/$SYSTEM_ENTRY"
        runCatching {
            if (system.isNullOrBlank()) {
                http.deleteJson(path)
            } else {
                http.putJson(path, buildJsonObject { put("value", system) })
            }
        }
    }

    private fun filesJson(prompt: PromptRequest) = buildJsonArray {
        prompt.mentionedFiles.forEach { path ->
            add(
                buildJsonObject {
                    put("uri", "file://$path")
                    put("name", path.substringAfterLast('/'))
                },
            )
        }
        prompt.attachments.forEach { attachment ->
            add(
                buildJsonObject {
                    put("uri", attachment.dataUrl)
                    put("name", attachment.name)
                    put("source", buildJsonObject { put("type", "inline") })
                },
            )
        }
    }

    override suspend fun runCommand(sessionId: String, command: String, arguments: String, model: ModelRef?, agent: String?) {
        selectModelAndAgent(sessionId, model, agent)
        http.postJsonLong(
            "/api/session/${enc(sessionId)}/command",
            buildJsonObject {
                put("name", command)
                put("text", arguments)
            },
        )
    }

    override suspend fun commands(): List<SlashCommand> {
        val response = http.getJson("/api/command")
        val array = (response as? JsonObject)?.arr("data") ?: response as? JsonArray ?: return emptyList()
        return array.objects().mapNotNull { obj ->
            val name = obj.str("name") ?: return@mapNotNull null
            SlashCommand(name = name, description = obj.str("description"), source = obj.str("source"), hints = obj.strList("hints"))
        }
    }

    override suspend fun runShell(sessionId: String, command: String, agent: String, model: ModelRef?) {
        selectModelAndAgent(sessionId, model, agent)
        http.postJsonLong("/api/session/${enc(sessionId)}/shell", buildJsonObject { put("command", command) })
    }

    override suspend fun renameSession(sessionId: String, title: String) {
        http.patchJson("/api/session/${enc(sessionId)}", buildJsonObject { put("title", title) })
    }

    override suspend fun listDirectory(path: String): List<MentionCandidate> {
        val query = if (path.isBlank()) emptyMap() else mapOf("path" to path)
        val response = http.getJson("/api/fs/list", query) as? JsonObject ?: return emptyList()
        return entries(response).sortedWith(compareBy({ it.kind != MentionCandidate.Kind.DIRECTORY }, { it.value.lowercase() }))
    }

    override suspend fun findPaths(query: String): List<MentionCandidate> {
        val response = http.getJson(
            "/api/fs/find",
            mapOf("query" to query, "limit" to FIND_LIMIT.toString()),
        ) as? JsonObject ?: return emptyList()
        return entries(response)
    }

    private fun entries(response: JsonObject): List<MentionCandidate> =
        response.arr("data")?.objects().orEmpty().mapNotNull { obj ->
            val path = obj.str("path")?.replace('\\', '/') ?: return@mapNotNull null
            val directory = obj.str("type") == "directory" || path.endsWith("/")
            MentionCandidate(
                value = if (directory) path.trimEnd('/') + "/" else path,
                kind = if (directory) MentionCandidate.Kind.DIRECTORY else MentionCandidate.Kind.FILE,
            )
        }

    override suspend fun mcpServers(): List<McpServerInfo> {
        val response = http.getJson("/api/mcp") as? JsonObject ?: return emptyList()
        return response.arr("data")?.objects().orEmpty().mapNotNull { obj ->
            val name = obj.str("name") ?: return@mapNotNull null
            McpServerInfo(
                name = name,
                status = ConfigJson.mcpStatus(obj.str("status", "status")),
                error = obj.str("status", "error"),
            )
        }
    }

    override suspend fun mcpSave(name: String, config: McpConfig) {
        http.putJson("/api/experimental/mcp/${enc(name)}", buildJsonObject { put("config", ConfigJson.encodeMcpV2(config)) })
    }

    override suspend fun mcpRemove(name: String): Boolean {
        http.deleteJson("/api/experimental/mcp/${enc(name)}")
        return true
    }

    override suspend fun mcpConnect(name: String) {
        http.postJsonLong("/api/experimental/mcp/${enc(name)}/connect")
    }

    override suspend fun mcpDisconnect(name: String) {
        http.postJson("/api/experimental/mcp/${enc(name)}/disconnect")
    }

    override suspend fun agentDetails(): List<AgentDetail> {
        val response = http.getJson("/api/agent")
        val array = (response as? JsonObject)?.arr("data") ?: response as? JsonArray ?: return emptyList()
        return array.objects().mapNotNull { obj ->
            val name = obj.str("name") ?: obj.str("id") ?: return@mapNotNull null
            ConfigJson.decodeAgent(name, obj, null)
        }
    }

    override suspend fun findFiles(query: String): List<String> {
        val response = http.getJson(
            "/api/fs/find",
            mapOf("query" to query, "type" to "file", "limit" to FIND_LIMIT.toString()),
        ) as? JsonObject ?: return emptyList()
        val array = response.arr("data") ?: return emptyList()
        return array.objects().mapNotNull { it.str("path") }
    }

    override suspend fun revert(sessionId: String, messageId: String) {
        http.postJson("/api/session/${enc(sessionId)}/revert/stage", buildJsonObject { put("messageID", messageId) })
    }

    override suspend fun unrevert(sessionId: String) {
        http.deleteJson("/api/session/${enc(sessionId)}/revert")
    }

    override suspend fun fork(sessionId: String, beforeMessageId: String?): ServerSession {
        val response = http.postJson(
            "/api/session/${enc(sessionId)}/fork",
            buildJsonObject { if (beforeMessageId != null) put("before", beforeMessageId) },
        ) as? JsonObject ?: throw ApiException(null, "Malformed fork response")
        return toSession(response.obj("data") ?: response) ?: throw ApiException(null, "Fork response has no id")
    }

    override suspend fun share(sessionId: String): String =
        throw ApiException(null, "Share links are not available on opencode 2.x servers")

    override suspend fun unshare(sessionId: String) = Unit

    override suspend fun compact(sessionId: String, model: ModelRef?) {
        http.postJson("/api/session/${enc(sessionId)}/compact", buildJsonObject { })
    }

    override suspend fun createPty(cwd: String?, command: String?, args: List<String>, env: Map<String, String>): PtyInfo {
        val response = http.postJson("/api/pty", ptyBody(cwd, command, args, env)) as? JsonObject
            ?: throw ApiException(null, "Malformed PTY response")
        val obj = response.obj("data") ?: response
        return PtyInfo(id = obj.str("id") ?: throw ApiException(null, "PTY response has no id"), cwd = obj.str("cwd"))
    }

    override suspend fun resizePty(ptyId: String, rows: Int, cols: Int) {
        http.putJson(
            "/api/pty/${enc(ptyId)}",
            buildJsonObject {
                put(
                    "size",
                    buildJsonObject {
                        put("rows", rows)
                        put("cols", cols)
                    },
                )
            },
        )
    }

    override suspend fun removePty(ptyId: String) {
        http.deleteJson("/api/pty/${enc(ptyId)}")
    }

    override fun ptyConnectPath(ptyId: String): String = "/api/pty/${enc(ptyId)}/connect"

    override suspend fun pendingQuestions(): List<QuestionRequest> {
        val response = http.getJson("/api/question/request")
        val array = (response as? JsonObject)?.arr("data") ?: response as? JsonArray ?: return emptyList()
        return array.objects().mapNotNull(EventMapper::question)
    }

    override suspend fun replyQuestion(sessionId: String, requestId: String, answers: List<List<String>>) {
        http.postJson("/api/session/${enc(sessionId)}/question/${enc(requestId)}/reply", questionAnswers(answers))
    }

    override suspend fun rejectQuestion(sessionId: String, requestId: String) {
        http.postJson("/api/session/${enc(sessionId)}/question/${enc(requestId)}/reject")
    }

    override suspend fun sessionBusy(sessionId: String): Boolean? {
        val response = http.getJson("/api/session/active")
        val array = (response as? JsonObject)?.arr("data") ?: response as? JsonArray ?: return null
        return array.any { element ->
            val id = (element as? JsonObject)?.let { it.str("id") ?: it.str("sessionID") } ?: element.jsonPrimitiveOrNull()
            id == sessionId
        }
    }

    override suspend fun abort(sessionId: String) {
        http.postJson("/api/session/${enc(sessionId)}/interrupt")
    }

    override suspend fun diff(sessionId: String): List<FileDiffPayload> {
        val response = http.getJson("/api/session/${enc(sessionId)}/diff")
        val array = (response as? JsonObject)?.arr("data")
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
        val array = (response as? JsonObject)?.arr("data")
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
                contextLimit = obj.number("limit", "context")?.takeIf { it > 0 },
                outputLimit = obj.number("limit", "output")?.takeIf { it > 0 },
            )
        }
    }

    override suspend fun agents(): List<AgentOption> {
        val response = http.getJson("/api/agent")
        val array = (response as? JsonObject)?.arr("data")
            ?: response as? JsonArray
            ?: return emptyList()
        return array.objects().mapNotNull { obj ->
            val name = obj.str("name") ?: obj.str("id") ?: return@mapNotNull null
            if (obj.bool("hidden") == true) return@mapNotNull null
            AgentOption(name = name, description = obj.str("description"), mode = obj.str("mode"))
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
            revertMessageId = obj.str("revert", "messageID"),
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
                parts = listOf(MessagePart.Text("$id:user", obj.str("text").orEmpty())) +
                    obj.arr("files")?.objects().orEmpty().mapIndexed { index, file ->
                        MessagePart.File(
                            id = "$id:file-$index",
                            name = file.str("name") ?: file.str("uri")?.substringAfterLast('/') ?: "file",
                            mime = file.str("mime") ?: "application/octet-stream",
                        )
                    },
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
                    providerId = obj.obj("model")?.str("providerID"),
                    tokens = obj.obj("tokens").toTokenUsage(),
                    cost = obj.double("cost"),
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
                            // `output` is an object: {output: string, cursor, size}.
                            output = obj.str("output", "output") ?: obj.str("output"),
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
        const val FIND_LIMIT = 30

        /** Instruction entry carrying the user's system prompt profile. */
        const val SYSTEM_ENTRY = "android-system-prompt"

        fun enc(pathSegment: String): String =
            java.net.URLEncoder.encode(pathSegment, "UTF-8").replace("+", "%20")
    }
}
