package ai.opencode.android.data.remote

import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
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

    fun eventStream(): Flow<StreamSignal> = SseStream(http.sseClient, http.sseRequest(eventPath)).signals()
}

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
