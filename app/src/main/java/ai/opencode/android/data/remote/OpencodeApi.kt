package ai.opencode.android.data.remote

import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ApiFlavor
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.ServerInfo
import ai.opencode.android.data.model.ServerSession
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

    suspend fun sendPrompt(sessionId: String, text: String, model: ModelRef?, agent: String?)

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
