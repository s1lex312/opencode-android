package ai.opencode.android.data.repo

import ai.opencode.android.data.cache.SessionCache
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.ServerSession

/**
 * Typed facade the ViewModels talk to. Every call returns [ApiResult] so the UI never has to
 * interpret exceptions, and successful session lists are mirrored into [SessionCache].
 */
class SessionRepository(
    private val connection: ServerConnectionManager,
    private val cache: SessionCache,
) {

    suspend fun loadSessions(): ApiResult<List<ServerSession>> {
        val api = connection.api ?: return notConnected()
        val result = runApiCall { api.listSessions() }
        if (result is ApiResult.Ok) cache.save(api.baseUrl, result.value)
        return result
    }

    suspend fun cachedSessions(): List<ServerSession> = cache.load(connection.serverKey)

    suspend fun createSession(title: String? = null): ApiResult<ServerSession> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.createSession(title) }
    }

    suspend fun deleteSession(sessionId: String): ApiResult<Unit> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.deleteSession(sessionId) }
    }

    suspend fun messages(sessionId: String): ApiResult<List<ChatMessage>> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.messages(sessionId) }
    }

    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        model: ModelRef?,
        agent: String?,
    ): ApiResult<Unit> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.sendPrompt(sessionId, text, model, agent) }
    }

    suspend fun abort(sessionId: String): ApiResult<Unit> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.abort(sessionId) }
    }

    suspend fun diff(sessionId: String): ApiResult<List<FileDiffPayload>> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.diff(sessionId) }
    }

    suspend fun models(): ApiResult<List<ModelOption>> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.models() }
    }

    suspend fun replyPermission(
        sessionId: String,
        permissionId: String,
        decision: PermissionDecision,
    ): ApiResult<Unit> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.replyPermission(sessionId, permissionId, decision) }
    }

    private fun notConnected(): ApiResult.Err = ApiResult.Err(
        message = "Not connected to an opencode server",
        notConnected = true,
    )
}
