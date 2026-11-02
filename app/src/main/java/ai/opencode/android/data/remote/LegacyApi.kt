package ai.opencode.android.data.remote

import ai.opencode.android.data.events.EventMapper
import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PromptRequest
import ai.opencode.android.data.model.PtyInfo
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ServerInfo
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.model.SlashCommand
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client for the "classic" opencode REST API used by 0.x and 1.x servers:
 * `/session`, `/session/{id}/message`, `/session/{id}/diff`, `/event`, `/config/providers`.
 */
class LegacyApi(override val http: ServerHttp) : OpencodeApi {

    override val flavor: ApiFlavor = ApiFlavor.LEGACY
    override val baseUrl: String get() = http.baseUrl
    override val eventPath: String = "/event"

    override suspend fun probe(): ServerInfo {
        val config = http.getJson("/config") as? JsonObject
            ?: throw ApiException(null, "Unexpected response from /config")
        val version = runCatching { (http.getJson("/global/health") as? JsonObject)?.str("version") }.getOrNull()
            ?: runCatching { (http.getJson("/app") as? JsonObject)?.str("version") }.getOrNull()
        return ServerInfo(
            flavor = ApiFlavor.LEGACY,
            version = version,
            directory = config.str("directory") ?: config.str("worktree"),
        )
    }

    override suspend fun listSessions(): List<ServerSession> {
        val response = http.getJson("/session")
        val array = response as? JsonArray ?: return emptyList()
        val busyIds = runCatching { busySessions() }.getOrDefault(emptySet())
        return array.objects().mapNotNull { obj -> toSession(obj)?.let { it.copy(busy = it.id in busyIds) } }
    }

    override suspend fun session(sessionId: String): ServerSession {
        val obj = http.getJson("/session/${enc(sessionId)}") as? JsonObject
            ?: throw ApiException(null, "Malformed session response")
        return toSession(obj) ?: throw ApiException(null, "Session response has no id")
    }

    private fun toSession(obj: JsonObject): ServerSession? {
        val id = obj.str("id") ?: return null
        return ServerSession(
            id = id,
            title = obj.str("title") ?: "Untitled session",
            directory = obj.str("directory"),
            created = obj.long("time", "created") ?: 0L,
            updated = obj.long("time", "updated") ?: obj.long("time", "created") ?: 0L,
            parentID = obj.str("parentID"),
            shareUrl = obj.str("share", "url"),
            revertMessageId = obj.str("revert", "messageID"),
        )
    }

    private suspend fun busySessions(): Set<String> {
        val status = http.getJson("/session/status") as? JsonObject ?: return emptySet()
        return status.entries.mapNotNull { (id, value) ->
            val type = (value as? JsonObject)?.str("type")
            if (type != null && type != "idle") id else null
        }.toSet()
    }

    override suspend fun createSession(title: String?): ServerSession {
        val body = buildJsonObject { if (!title.isNullOrBlank()) put("title", title) }
        val obj = http.postJson("/session", body) as? JsonObject
            ?: throw ApiException(null, "Malformed session response")
        return toSession(obj) ?: throw ApiException(null, "Session response has no id")
    }

    override suspend fun deleteSession(sessionId: String) {
        http.deleteJson("/session/${enc(sessionId)}")
    }

    override suspend fun messages(sessionId: String): List<ChatMessage> {
        val response = http.getJson(
            "/session/${enc(sessionId)}/message",
            mapOf("limit" to MESSAGE_LIMIT.toString()),
        )
        val array = response as? JsonArray ?: return emptyList()
        return array.objects().mapNotNull(::toMessage)
    }

    override suspend fun sendPrompt(sessionId: String, prompt: PromptRequest) {
        val body = buildJsonObject {
            put("parts", buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", prompt.text)
                    },
                )
                prompt.mentionedFiles.forEach { path ->
                    add(
                        buildJsonObject {
                            put("type", "file")
                            put("mime", "text/plain")
                            put("filename", path.substringAfterLast('/'))
                            put("url", "file://$path")
                        },
                    )
                }
                prompt.attachments.forEach { attachment ->
                    add(
                        buildJsonObject {
                            put("type", "file")
                            put("mime", attachment.mime)
                            put("filename", attachment.name)
                            put("url", attachment.dataUrl)
                        },
                    )
                }
            })
            modelJson(prompt.model)?.let { put("model", it) }
            if (!prompt.agent.isNullOrBlank()) put("agent", prompt.agent)
            if (!prompt.system.isNullOrBlank()) put("system", prompt.system)
        }
        // Prefer the fire-and-forget endpoint (1.x). Older servers only have the blocking one;
        // SSE still streams the answer, so the blocking call just runs inside our IO coroutine.
        try {
            http.postJson("/session/${enc(sessionId)}/prompt_async", body)
        } catch (notFound: NotFoundException) {
            http.postJson("/session/${enc(sessionId)}/message", body)
        } catch (methodNotAllowed: ApiException) {
            if (methodNotAllowed.statusCode == 405) {
                http.postJson("/session/${enc(sessionId)}/message", body)
            } else {
                throw methodNotAllowed
            }
        }
    }

    override suspend fun runCommand(sessionId: String, command: String, arguments: String, model: ModelRef?, agent: String?) {
        http.postJson(
            "/session/${enc(sessionId)}/command",
            buildJsonObject {
                put("command", command)
                put("arguments", arguments)
                if (model != null && model.providerID.isNotBlank() && model.modelID.isNotBlank()) put("model", model.key)
                if (!agent.isNullOrBlank()) put("agent", agent)
            },
        )
    }

    override suspend fun commands(): List<SlashCommand> {
        val array = http.getJson("/command") as? JsonArray ?: return emptyList()
        return array.objects().mapNotNull { obj ->
            val name = obj.str("name") ?: return@mapNotNull null
            SlashCommand(name = name, description = obj.str("description"), source = obj.str("source"))
        }
    }

    override suspend fun findFiles(query: String): List<String> {
        val array = http.getJson(
            "/find/file",
            mapOf("query" to query, "dirs" to "false", "limit" to FIND_LIMIT.toString()),
        ) as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    override suspend fun revert(sessionId: String, messageId: String) {
        http.postJson("/session/${enc(sessionId)}/revert", buildJsonObject { put("messageID", messageId) })
    }

    override suspend fun unrevert(sessionId: String) {
        http.postJson("/session/${enc(sessionId)}/unrevert")
    }

    override suspend fun fork(sessionId: String, beforeMessageId: String?): ServerSession {
        val obj = http.postJson(
            "/session/${enc(sessionId)}/fork",
            buildJsonObject { if (beforeMessageId != null) put("messageID", beforeMessageId) },
        ) as? JsonObject ?: throw ApiException(null, "Malformed fork response")
        return toSession(obj) ?: throw ApiException(null, "Fork response has no id")
    }

    override suspend fun share(sessionId: String): String {
        val obj = http.postJson("/session/${enc(sessionId)}/share") as? JsonObject
        return obj?.str("share", "url") ?: throw ApiException(null, "The server did not return a share link")
    }

    override suspend fun unshare(sessionId: String) {
        http.deleteJson("/session/${enc(sessionId)}/share")
    }

    override suspend fun compact(sessionId: String, model: ModelRef?) {
        val target = model ?: lastModel(sessionId)
            ?: throw ApiException(null, "Pick a model first: compaction needs one")
        http.postJson(
            "/session/${enc(sessionId)}/summarize",
            buildJsonObject {
                put("providerID", target.providerID)
                put("modelID", target.modelID)
            },
        )
    }

    /** Model of the latest assistant turn, used when the user relies on the server default. */
    private suspend fun lastModel(sessionId: String): ModelRef? {
        val last = messages(sessionId).lastOrNull { it.role == Role.ASSISTANT && it.providerId != null && it.model != null }
            ?: return null
        return ModelRef(last.providerId.orEmpty(), last.model.orEmpty())
    }

    override suspend fun createPty(cwd: String?): PtyInfo {
        val obj = http.postJson("/pty", buildJsonObject { if (!cwd.isNullOrBlank()) put("cwd", cwd) }) as? JsonObject
            ?: throw ApiException(null, "Malformed PTY response")
        return PtyInfo(id = obj.str("id") ?: throw ApiException(null, "PTY response has no id"), cwd = obj.str("cwd"))
    }

    override suspend fun resizePty(ptyId: String, rows: Int, cols: Int) {
        http.putJson(
            "/pty/${enc(ptyId)}",
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
        http.deleteJson("/pty/${enc(ptyId)}")
    }

    override fun ptyConnectPath(ptyId: String): String = "/pty/${enc(ptyId)}/connect"

    override suspend fun abort(sessionId: String) {
        http.postJson("/session/${enc(sessionId)}/abort")
    }

    override suspend fun diff(sessionId: String): List<FileDiffPayload> {
        val response = http.getJson("/session/${enc(sessionId)}/diff")
        val array = response as? JsonArray ?: return emptyList()
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
        val providers = runCatching { http.getJson("/config/providers") as? JsonObject }.getOrNull()
        val list = mutableListOf<ModelOption>()
        providers?.arr("providers")?.objects()?.forEach { provider ->
            val providerID = provider.str("id") ?: return@forEach
            val providerName = provider.str("name") ?: providerID
            provider.obj("models")?.forEach { (modelKey, value) ->
                val model = value as? JsonObject
                val modelID = model?.str("id") ?: modelKey
                val name = model?.str("name") ?: modelID
                list += ModelOption(
                    providerID = providerID,
                    modelID = modelID,
                    name = "$providerName · $name",
                    contextLimit = model?.number("limit", "context")?.takeIf { it > 0 },
                )
            }
        }
        return list
    }

    override suspend fun agents(): List<AgentOption> {
        val response = http.getJson("/agent")
        val array = (response as? JsonObject)?.obj("data") as? JsonArray
            ?: response as? JsonArray
            ?: return emptyList()
        return array.objects().mapNotNull { obj ->
            val name = obj.str("name") ?: obj.str("id") ?: return@mapNotNull null
            if (obj.bool("hidden") == true) return@mapNotNull null
            AgentOption(name = name, description = obj.str("description"), mode = obj.str("mode"))
        }
    }

    override suspend fun replyPermission(sessionId: String, permissionId: String, decision: PermissionDecision) {
        // 1.x since the permission rework: POST /permission/{requestID}/reply. Older servers only
        // know the per-session route.
        try {
            http.postJson("/permission/${enc(permissionId)}/reply", buildJsonObject { put("reply", decision.wire) })
        } catch (notFound: NotFoundException) {
            http.postJson(
                "/session/${enc(sessionId)}/permissions/${enc(permissionId)}",
                buildJsonObject { put("response", decision.wire) },
            )
        }
    }

    private fun modelJson(model: ModelRef?): JsonObject? {
        if (model == null || model.providerID.isBlank() || model.modelID.isBlank()) return null
        return buildJsonObject {
            put("providerID", model.providerID)
            put("modelID", model.modelID)
        }
    }

    // ------------------------------------------------------------------ mapping

    private fun toMessage(entry: JsonObject): ChatMessage? {
        val info = entry.obj("info") ?: return null
        val id = info.str("id") ?: return null
        val role = if (info.str("role") == "user") Role.USER else Role.ASSISTANT
        val parts: List<MessagePart> = entry.arr("parts")?.objects()
            ?.mapNotNull { EventMapper.legacyPart(it) }
            .orEmpty()
        return ChatMessage(
            id = id,
            role = role,
            created = info.long("time", "created") ?: 0L,
            parts = parts,
            error = info.obj("error")?.let { error ->
                error.str("data", "message") ?: error.str("message") ?: error.str("name")
            },
            model = info.str("modelID"),
            providerId = info.str("providerID"),
            tokens = info.obj("tokens").toTokenUsage(),
            cost = info.double("cost"),
        )
    }

    private companion object {
        const val MESSAGE_LIMIT = 200
        const val FIND_LIMIT = 30

        fun enc(pathSegment: String): String =
            java.net.URLEncoder.encode(pathSegment, "UTF-8").replace("+", "%20")
    }
}
