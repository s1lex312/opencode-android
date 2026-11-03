package ai.opencode.android.data.repo

import ai.opencode.android.data.cache.SessionCache
import ai.opencode.android.data.mcp.McpMonitor
import ai.opencode.android.data.model.AgentDetail
import ai.opencode.android.data.model.AgentOption
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
import ai.opencode.android.data.model.QuestionRequest
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.model.SlashCommand
import kotlinx.serialization.json.JsonObject

/**
 * Typed facade the ViewModels talk to. Every call returns [ApiResult] so the UI never has to
 * interpret exceptions, and successful session lists are mirrored into [SessionCache].
 */
class SessionRepository(
    private val connection: ServerConnectionManager,
    private val cache: SessionCache,
    private val mcpMonitor: McpMonitor = McpMonitor(),
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

    suspend fun pendingQuestions(): ApiResult<List<QuestionRequest>> = call { pendingQuestions() }

    suspend fun replyQuestion(sessionId: String, requestId: String, answers: List<List<String>>): ApiResult<Unit> =
        call { replyQuestion(sessionId, requestId, answers) }

    suspend fun rejectQuestion(sessionId: String, requestId: String): ApiResult<Unit> = call { rejectQuestion(sessionId, requestId) }

    suspend fun sessionBusy(sessionId: String): ApiResult<Boolean?> = call { sessionBusy(sessionId) }

    suspend fun messageDiff(sessionId: String, messageId: String): ApiResult<List<FileDiffPayload>> = call { messageDiff(sessionId, messageId) }

    suspend fun runShell(sessionId: String, command: String, agent: String, model: ModelRef?): ApiResult<Unit> =
        call { runShell(sessionId, command, agent, model) }

    suspend fun renameSession(sessionId: String, title: String): ApiResult<Unit> = call { renameSession(sessionId, title) }

    suspend fun listDirectory(path: String): ApiResult<List<MentionCandidate>> = call { listDirectory(path) }

    suspend fun findPaths(query: String): ApiResult<List<MentionCandidate>> = call { findPaths(query) }

    suspend fun mcpServers(): ApiResult<List<McpServerInfo>> =
        call { mcpServers() }.also { if (it is ApiResult.Ok) mcpMonitor.observe(it.value) }

    /** Saving an enabled server connects it, so it is timed like a connect. */
    suspend fun mcpSave(name: String, config: McpConfig): ApiResult<Unit> =
        if (config.enabled) mcpMonitor.timed(name, { call { mcpSave(name, config) } }, ::errorOf) else call { mcpSave(name, config) }

    suspend fun mcpRemove(name: String): ApiResult<Boolean> = call { mcpRemove(name) }

    suspend fun mcpConnect(name: String): ApiResult<Unit> = mcpMonitor.timed(name, { call { mcpConnect(name) } }, ::errorOf)

    private fun errorOf(result: ApiResult<*>): String? = (result as? ApiResult.Err)?.message

    suspend fun mcpDisconnect(name: String): ApiResult<Unit> = call { mcpDisconnect(name) }

    suspend fun mcpAuthenticate(name: String): ApiResult<Unit> = call { mcpAuthenticate(name) }

    suspend fun mcpAuthCode(name: String, code: String): ApiResult<Unit> = call { mcpAuthCode(name, code) }

    suspend fun mcpLogout(name: String): ApiResult<Unit> = call { mcpLogout(name) }

    /** `null` while disconnected. */
    val supportsConfig: Boolean? get() = connection.api?.supportsConfig

    suspend fun globalConfig(): ApiResult<JsonObject> = call { globalConfig() }

    suspend fun patchGlobalConfig(patch: JsonObject): ApiResult<Unit> = call { patchGlobalConfig(patch) }

    suspend fun agentDetails(): ApiResult<List<AgentDetail>> = call { agentDetails() }

    suspend fun toolIds(): ApiResult<List<String>> = call { toolIds() }

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
