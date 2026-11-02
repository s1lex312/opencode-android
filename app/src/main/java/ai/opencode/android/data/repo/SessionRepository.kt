package ai.opencode.android.data.repo

import ai.opencode.android.data.cache.SessionCache
import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PromptRequest
import ai.opencode.android.data.model.PtyInfo
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.model.SlashCommand

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

    suspend fun session(sessionId: String): ApiResult<ServerSession> = call { session(sessionId) }

    suspend fun sendPrompt(sessionId: String, prompt: PromptRequest): ApiResult<Unit> =
        call { sendPrompt(sessionId, prompt) }

    suspend fun runCommand(
        sessionId: String,
        command: String,
        arguments: String,
        model: ModelRef?,
        agent: String?,
    ): ApiResult<Unit> = call { runCommand(sessionId, command, arguments, model, agent) }

    suspend fun commands(): ApiResult<List<SlashCommand>> = call { commands() }

    suspend fun findFiles(query: String): ApiResult<List<String>> = call { findFiles(query) }

    suspend fun revert(sessionId: String, messageId: String): ApiResult<Unit> = call { revert(sessionId, messageId) }

    suspend fun unrevert(sessionId: String): ApiResult<Unit> = call { unrevert(sessionId) }

    suspend fun fork(sessionId: String, beforeMessageId: String?): ApiResult<ServerSession> =
        call { fork(sessionId, beforeMessageId) }

    suspend fun share(sessionId: String): ApiResult<String> = call { share(sessionId) }

    suspend fun unshare(sessionId: String): ApiResult<Unit> = call { unshare(sessionId) }

    suspend fun compact(sessionId: String, model: ModelRef?): ApiResult<Unit> = call { compact(sessionId, model) }

    suspend fun createPty(cwd: String?): ApiResult<PtyInfo> = call { createPty(cwd) }

    private suspend fun <T> call(block: suspend ai.opencode.android.data.remote.OpencodeApi.() -> T): ApiResult<T> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.block() }
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

    suspend fun agents(): ApiResult<List<AgentOption>> {
        val api = connection.api ?: return notConnected()
        return runApiCall { api.agents() }
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
