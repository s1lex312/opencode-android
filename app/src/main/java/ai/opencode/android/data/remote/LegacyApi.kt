package ai.opencode.android.data.remote

import ai.opencode.android.data.events.EventMapper
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
        val version = runCatching {
            (http.getJson("/app") as? JsonObject)?.str("version")
        }.getOrNull()
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
        return array.objects().mapNotNull { obj ->
            val id = obj.str("id") ?: return@mapNotNull null
            ServerSession(
                id = id,
                title = obj.str("title") ?: "Untitled session",
                directory = obj.str("directory"),
                created = obj.long("time", "created") ?: 0L,
                updated = obj.long("time", "updated") ?: obj.long("time", "created") ?: 0L,
                parentID = obj.str("parentID"),
                busy = id in busyIds,
            )
        }
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
        return ServerSession(
            id = obj.str("id") ?: throw ApiException(null, "Session response has no id"),
            title = obj.str("title") ?: "New session",
            directory = obj.str("directory"),
            created = obj.long("time", "created") ?: 0L,
            updated = obj.long("time", "updated") ?: 0L,
        )
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

    override suspend fun sendPrompt(sessionId: String, text: String, model: ModelRef?, agent: String?) {
        val body = buildJsonObject {
            put("parts", kotlinx.serialization.json.buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            })
            if (model != null && model.providerID.isNotBlank() && model.modelID.isNotBlank()) {
                put(
                    "model",
                    buildJsonObject {
                        put("providerID", model.providerID)
                        put("modelID", model.modelID)
                    },
                )
            }
            if (!agent.isNullOrBlank()) put("agent", agent)
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
                list += ModelOption(providerID = providerID, modelID = modelID, name = "$providerName · $name")
            }
        }
        return list
    }

    override suspend fun replyPermission(sessionId: String, permissionId: String, decision: PermissionDecision) {
        http.postJson(
            "/session/${enc(sessionId)}/permissions/${enc(permissionId)}",
            buildJsonObject { put("response", decision.wire) },
        )
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
        )
    }

    private companion object {
        const val MESSAGE_LIMIT = 200

        fun enc(pathSegment: String): String =
            java.net.URLEncoder.encode(pathSegment, "UTF-8").replace("+", "%20")
    }
}
