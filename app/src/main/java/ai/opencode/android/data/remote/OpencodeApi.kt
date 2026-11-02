package ai.opencode.android.data.remote

import ai.opencode.android.data.model.AgentDetail
import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.McpConfig
import ai.opencode.android.data.model.McpServerInfo
import ai.opencode.android.data.model.MentionCandidate
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PromptRequest
import ai.opencode.android.data.model.PtyInfo
import ai.opencode.android.data.model.ServerInfo
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.model.SlashCommand
import ai.opencode.android.security.NetworkPolicy
import ai.opencode.android.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * The subset of the opencode server API the mobile client needs. Implemented twice:
 * [V2Api] for opencode 2.x (`/api/...`) and [LegacyApi] for 0.x/1.x (`/session`, `/event`).
 */
interface OpencodeApi {
    val flavor: ApiFlavor
    val baseUrl: String
    val http: ServerHttp

    /** Path of the SSE endpoint for this flavour. */
    val eventPath: String

    suspend fun probe(): ServerInfo

    suspend fun listSessions(): List<ServerSession>

    suspend fun createSession(title: String? = null): ServerSession

    suspend fun deleteSession(sessionId: String)

    suspend fun messages(sessionId: String): List<ChatMessage>

    suspend fun session(sessionId: String): ServerSession

    suspend fun sendPrompt(sessionId: String, prompt: PromptRequest)

    /** Runs a server slash command (`/name arguments`) in the session. */
    suspend fun runCommand(sessionId: String, command: String, arguments: String, model: ModelRef?, agent: String?)

    suspend fun commands(): List<SlashCommand>

    /** Project-relative paths matching [query], for `@` mentions. */
    suspend fun findFiles(query: String): List<String>

    /** Hides [messageId] and everything after it and restores the files it changed. */
    suspend fun revert(sessionId: String, messageId: String)

    suspend fun unrevert(sessionId: String)

    /** New session with the history before [beforeMessageId] (everything when `null`). */
    suspend fun fork(sessionId: String, beforeMessageId: String?): ServerSession

    /** Returns the public URL. */
    suspend fun share(sessionId: String): String

    suspend fun unshare(sessionId: String)

    /** Summarises the conversation to free the context window. */
    suspend fun compact(sessionId: String, model: ModelRef?)

    suspend fun createPty(cwd: String?): PtyInfo

    suspend fun resizePty(ptyId: String, rows: Int, cols: Int)

    suspend fun removePty(ptyId: String)

    /** Path of the PTY WebSocket. */
    fun ptyConnectPath(ptyId: String): String

    suspend fun abort(sessionId: String)

    suspend fun diff(sessionId: String): List<FileDiffPayload>

    suspend fun models(): List<ModelOption>

    suspend fun agents(): List<AgentOption>

    suspend fun replyPermission(sessionId: String, permissionId: String, decision: PermissionDecision)

    /** Runs [command] in the project shell as part of the session (the TUI's `!command`). */
    suspend fun runShell(sessionId: String, command: String, agent: String, model: ModelRef?): Unit = unsupported("Shell commands")

    suspend fun renameSession(sessionId: String, title: String): Unit = unsupported("Renaming chats")

    /** Entries of a project directory (`""` is the root), for the `@` popup before anything is typed. */
    suspend fun listDirectory(path: String): List<MentionCandidate> = emptyList()

    /** Files and directories matching [query]. */
    suspend fun findPaths(query: String): List<MentionCandidate> =
        findFiles(query).map { MentionCandidate(it, MentionCandidate.Kind.FILE) }

    // ------------------------------------------------------------------ MCP

    suspend fun mcpServers(): List<McpServerInfo> = unsupported("MCP")

    /** Creates or replaces the server in the global config and (re)connects it. */
    suspend fun mcpSave(name: String, config: McpConfig): Unit = unsupported("MCP")

    /** Removes the server where the API can, otherwise disables it. Returns true when removed. */
    suspend fun mcpRemove(name: String): Boolean = unsupported("MCP")

    suspend fun mcpConnect(name: String): Unit = unsupported("MCP")

    suspend fun mcpDisconnect(name: String): Unit = unsupported("MCP")

    /** Starts OAuth; the server reports the URL to open via `mcp.browser.open.failed`. Blocks until done. */
    suspend fun mcpAuthenticate(name: String): Unit = unsupported("MCP OAuth")

    /** Finishes OAuth with a code pasted by the user (when the redirect cannot reach the server). */
    suspend fun mcpAuthCode(name: String, code: String): Unit = unsupported("MCP OAuth")

    suspend fun mcpLogout(name: String): Unit = unsupported("MCP OAuth")

    // ------------------------------------------------------------------ server config

    /** Whether [globalConfig]/[patchGlobalConfig] work on this server. */
    val supportsConfig: Boolean get() = false

    suspend fun globalConfig(): JsonObject = unsupported("Editing the opencode config")

    /** Deep-merges [patch] into the global config (`~/.config/opencode/opencode.json`). */
    suspend fun patchGlobalConfig(patch: JsonObject): Unit = unsupported("Editing the opencode config")

    /** Effective agents including subagents and hidden ones. */
    suspend fun agentDetails(): List<AgentDetail> =
        agents().map { AgentDetail(name = it.name, description = it.description, mode = it.mode ?: "all") }

    /** Ids of the tools agents can call (built-in and MCP). */
    suspend fun toolIds(): List<String> = emptyList()

    fun eventStream(): Flow<StreamSignal> = SseStream(http.sseClient, http.sseRequest(eventPath)).signals()
}

private fun unsupported(feature: String): Nothing =
    throw UnsupportedFeatureException("$feature: not supported by this opencode server version")

/**
 * Creates an [OpencodeApi] for the configured server, detecting which API flavour it speaks.
 *
 * Detection order: `GET /api/info` (v2) → `GET /config` (legacy). A 401 is reported immediately
 * (retrying another path cannot help); a 404/400 means "not this flavour".
 */
data class ConnectedServer(val api: OpencodeApi, val info: ServerInfo)

class ApiFactory(
    private val settings: AppSettings,
    private val password: String?,
    private val token: String?,
) {

    suspend fun connect(): ConnectedServer {
        val policy = NetworkPolicy.normalize(settings.serverUrl, settings.allowPrivateNetworkCleartext)
        if (policy is NetworkPolicy.Result.Rejected) {
            throw ApiException(null, policy.reason)
        }
        val allowed = policy as NetworkPolicy.Result.Allowed
        val credentials = ServerCredentials(
            authMode = settings.authMode,
            username = settings.username.ifBlank { "opencode" },
            password = password,
            token = token,
        )
        val http = ServerHttp(
            baseUrl = allowed.url,
            credentials = credentials,
            allowPrivateNetworkCleartext = settings.allowPrivateNetworkCleartext,
            timeoutSeconds = settings.requestTimeoutSeconds.coerceIn(15, 900).toLong(),
        )

        val v2 = V2Api(http)
        try {
            val info = v2.probe()
            return ConnectedServer(v2, info)
        } catch (unauthorized: UnauthorizedException) {
            throw unauthorized
        } catch (api: ApiException) {
            // 404/405 => this server predates the v2 HttpApi; anything else is also worth a
            // single legacy attempt because the legacy surface is what 0.x/1.x servers expose.
            if (api.statusCode == 401 || api.statusCode == 403) throw api
        }

        val legacy = LegacyApi(http)
        val info = legacy.probe()
        return ConnectedServer(legacy, info)
    }
}
