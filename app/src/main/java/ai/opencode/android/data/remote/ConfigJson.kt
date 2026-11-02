package ai.opencode.android.data.remote

import ai.opencode.android.data.model.AgentDetail
import ai.opencode.android.data.model.BehaviourConfig
import ai.opencode.android.data.model.McpConfig
import ai.opencode.android.data.model.McpServerInfo
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Translates between opencode's config JSON (`opencode.json`, `PATCH /global/config`) and the
 * app's models. The server deep-merges patches, so every encoder emits only the keys it owns.
 */
object ConfigJson {

    /** Permission keys opencode knows, in the order the editors show them. */
    val PERMISSION_KEYS = listOf(
        "edit", "bash", "webfetch", "websearch", "external_directory", "task", "read", "glob", "grep",
        "list", "lsp", "skill", "todowrite", "question", "doom_loop",
    )

    // ------------------------------------------------------------------ MCP

    fun decodeMcp(obj: JsonObject?): McpConfig? {
        obj ?: return null
        val enabled = obj.bool("enabled") ?: (obj.bool("disabled")?.not()) ?: true
        val timeout = obj.int("timeout") ?: obj.int("timeout", "execution")
        return when (obj.str("type")) {
            "local" -> McpConfig.Local(
                command = obj.strList("command"),
                environment = obj.stringMap("environment"),
                cwd = obj.str("cwd"),
                enabled = enabled,
                timeoutMs = timeout,
            )

            "remote" -> McpConfig.Remote(
                url = obj.str("url").orEmpty(),
                headers = obj.stringMap("headers"),
                oauth = (obj["oauth"] as? JsonPrimitive)?.booleanOrNull != false,
                enabled = enabled,
                timeoutMs = timeout,
            )

            else -> null
        }
    }

    /** 1.x `mcp.<name>` entry. */
    fun encodeMcp(config: McpConfig): JsonObject = buildJsonObject {
        when (config) {
            is McpConfig.Local -> {
                put("type", "local")
                put("command", buildJsonArray { config.command.forEach { add(JsonPrimitive(it)) } })
                if (config.environment.isNotEmpty()) put("environment", stringObject(config.environment))
                config.cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            }

            is McpConfig.Remote -> {
                put("type", "remote")
                put("url", config.url)
                if (config.headers.isNotEmpty()) put("headers", stringObject(config.headers))
                if (!config.oauth) put("oauth", false)
            }
        }
        put("enabled", config.enabled)
        config.timeoutMs?.takeIf { it > 0 }?.let { put("timeout", it) }
    }

    /** 2.x `Mcp.*ConfigEncoded`: `disabled` instead of `enabled`, timeouts per phase. */
    fun encodeMcpV2(config: McpConfig): JsonObject = buildJsonObject {
        when (config) {
            is McpConfig.Local -> {
                put("type", "local")
                put("command", buildJsonArray { config.command.forEach { add(JsonPrimitive(it)) } })
                if (config.environment.isNotEmpty()) put("environment", stringObject(config.environment))
                config.cwd?.takeIf { it.isNotBlank() }?.let { put("cwd", it) }
            }

            is McpConfig.Remote -> {
                put("type", "remote")
                put("url", config.url)
                if (config.headers.isNotEmpty()) put("headers", stringObject(config.headers))
                if (!config.oauth) put("oauth", false)
            }
        }
        if (!config.enabled) put("disabled", true)
        config.timeoutMs?.takeIf { it > 0 }?.let { timeout ->
            put("timeout", buildJsonObject { put("execution", timeout) })
        }
    }

    fun mcpStatus(raw: String?): McpServerInfo.Status = when (raw) {
        "connected" -> McpServerInfo.Status.CONNECTED
        "pending" -> McpServerInfo.Status.PENDING
        "disabled" -> McpServerInfo.Status.DISABLED
        "failed" -> McpServerInfo.Status.FAILED
        "needs_auth" -> McpServerInfo.Status.NEEDS_AUTH
        "needs_client_registration" -> McpServerInfo.Status.NEEDS_REGISTRATION
        else -> McpServerInfo.Status.UNKNOWN
    }

    // ------------------------------------------------------------------ agents

    /**
     * [effective] is one entry of `GET /agent`; [override] is `agent.<name>` from the config the
     * app writes to. Overrides win because they are what the editor shows and saves.
     */
    fun decodeAgent(name: String, effective: JsonObject?, override: JsonObject?): AgentDetail {
        val modelString = override?.str("model")
        val model = modelString?.let(::parseModel)
            ?: effective?.obj("model")?.let { obj ->
                val provider = obj.str("providerID")
                val id = obj.str("modelID") ?: obj.str("id")
                if (provider != null && id != null) ModelRef(provider, id) else null
            }
        return AgentDetail(
            name = name,
            description = override?.str("description") ?: effective?.str("description"),
            mode = override?.str("mode") ?: effective?.str("mode") ?: "all",
            native = effective?.bool("native") ?: false,
            hidden = override?.bool("hidden") ?: effective?.bool("hidden") ?: false,
            disabled = override?.bool("disable") ?: false,
            model = model,
            temperature = override?.double("temperature") ?: effective?.double("temperature"),
            topP = override?.double("top_p") ?: effective?.double("topP"),
            steps = override?.int("steps") ?: override?.int("maxSteps") ?: effective?.int("steps"),
            // Built-in agents' prompts ship with opencode: showing them would save a frozen copy.
            prompt = override?.str("prompt")
                ?: effective?.takeIf { it.bool("native") != true }?.let { it.str("prompt") ?: it.str("system") },
            color = override?.str("color") ?: effective?.str("color"),
            tools = override?.obj("tools")?.let { tools ->
                tools.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.booleanOrNull?.let { key to it } }.toMap()
            }.orEmpty(),
            permissions = decodePermissions(override?.get("permission")),
        )
    }

    fun encodeAgent(agent: AgentDetail): JsonObject = buildJsonObject {
        agent.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        put("mode", agent.mode)
        agent.model?.let { put("model", it.key) }
        agent.temperature?.let { put("temperature", it) }
        agent.topP?.let { put("top_p", it) }
        agent.steps?.takeIf { it > 0 }?.let { put("steps", it) }
        agent.prompt?.takeIf { it.isNotBlank() }?.let { put("prompt", it) }
        agent.color?.takeIf { it.isNotBlank() }?.let { put("color", it) }
        put("hidden", agent.hidden)
        put("disable", agent.disabled)
        if (agent.tools.isNotEmpty()) {
            put("tools", buildJsonObject { agent.tools.forEach { (tool, enabled) -> put(tool, enabled) } })
        }
        if (agent.permissions.isNotEmpty()) put("permission", encodePermissions(agent.permissions))
    }

    fun parseModel(value: String): ModelRef? {
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.length - 1) return null
        return ModelRef(value.substring(0, slash), value.substring(slash + 1))
    }

    // ------------------------------------------------------------------ permissions & behaviour

    /** `"allow"` (everything) or `{edit: "ask", bash: {"*": "ask", "git *": "allow"}}`. */
    fun decodePermissions(element: kotlinx.serialization.json.JsonElement?): Map<String, PermissionAction> {
        when (element) {
            is JsonPrimitive -> {
                val action = PermissionAction.of(element.content) ?: return emptyMap()
                return PERMISSION_KEYS.associateWith { action }
            }

            is JsonObject -> return element.mapNotNull { (key, value) ->
                val action = when (value) {
                    is JsonPrimitive -> PermissionAction.of(value.content)
                    // Pattern maps: the catch-all rule is the closest single answer.
                    is JsonObject -> (value["*"] as? JsonPrimitive)?.content?.let(PermissionAction::of)
                    else -> null
                }
                action?.let { key to it }
            }.toMap()

            else -> return emptyMap()
        }
    }

    fun encodePermissions(permissions: Map<String, PermissionAction>): JsonObject = buildJsonObject {
        permissions.forEach { (key, action) -> put(key, action.wire) }
    }

    fun decodeBehaviour(config: JsonObject): BehaviourConfig = BehaviourConfig(
        permissions = decodePermissions(config["permission"]),
        tools = config.obj("tools")?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.booleanOrNull?.let { key to it }
        }?.toMap().orEmpty(),
        smallModel = config.str("small_model"),
        defaultAgent = config.str("default_agent"),
        autoCompact = config.bool("compaction", "auto"),
        prune = config.bool("compaction", "prune"),
        reservedTokens = config.int("compaction", "reserved"),
        snapshot = config.bool("snapshot"),
        share = config.str("share"),
        instructions = config.strList("instructions"),
        subagentDepth = config.int("subagent_depth"),
        username = config.str("username"),
    )

    /** Patch with every field the behaviour screen manages that has a value. */
    fun encodeBehaviour(behaviour: BehaviourConfig): JsonObject = buildJsonObject {
        if (behaviour.permissions.isNotEmpty()) put("permission", encodePermissions(behaviour.permissions))
        if (behaviour.tools.isNotEmpty()) {
            put("tools", buildJsonObject { behaviour.tools.forEach { (tool, enabled) -> put(tool, enabled) } })
        }
        behaviour.smallModel?.takeIf { it.contains('/') }?.let { put("small_model", it) }
        behaviour.defaultAgent?.takeIf { it.isNotBlank() }?.let { put("default_agent", it) }
        if (behaviour.autoCompact != null || behaviour.prune != null || behaviour.reservedTokens != null) {
            put(
                "compaction",
                buildJsonObject {
                    behaviour.autoCompact?.let { put("auto", it) }
                    behaviour.prune?.let { put("prune", it) }
                    behaviour.reservedTokens?.takeIf { it >= 0 }?.let { put("reserved", it) }
                },
            )
        }
        behaviour.snapshot?.let { put("snapshot", it) }
        behaviour.share?.takeIf { it in setOf("manual", "auto", "disabled") }?.let { put("share", it) }
        if (behaviour.instructions.isNotEmpty()) {
            put("instructions", buildJsonArray { behaviour.instructions.forEach { add(JsonPrimitive(it)) } })
        }
        behaviour.subagentDepth?.takeIf { it > 0 }?.let { put("subagent_depth", it) }
        behaviour.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
    }

    /** `provider.<p>.models.<m>.limit` so opencode's own auto-compaction uses the same window. */
    fun encodeModelLimit(model: ModelRef, context: Long, output: Long): JsonObject = buildJsonObject {
        put(
            "provider",
            buildJsonObject {
                put(
                    model.providerID,
                    buildJsonObject {
                        put(
                            "models",
                            buildJsonObject {
                                put(
                                    model.modelID,
                                    buildJsonObject {
                                        put(
                                            "limit",
                                            buildJsonObject {
                                                put("context", context)
                                                put("output", output)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun stringObject(map: Map<String, String>) = buildJsonObject { map.forEach { (key, value) -> put(key, value) } }

    private fun JsonObject.stringMap(key: String): Map<String, String> =
        obj(key)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
}
