package ai.opencode.android.data.hooks

import ai.opencode.android.data.events.OpencodeEvent
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PermissionRequest
import ai.opencode.android.data.model.PromptRequest
import ai.opencode.android.data.model.ToolStatus
import ai.opencode.android.data.remote.ApiException
import ai.opencode.android.data.remote.OpencodeJson
import ai.opencode.android.data.remote.RemoteExec
import ai.opencode.android.data.remote.str
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.settings.Hook
import ai.opencode.android.settings.HookEvent
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.settings.Workbench
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** One execution of a hook, for the log on the Hooks screen. */
data class HookRun(
    val hookName: String,
    val event: HookEvent,
    val sessionId: String?,
    val target: String?,
    val exitCode: Int?,
    val output: String,
    val startedAt: Long,
    val durationMs: Long,
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0
}

/** What the prompt-submit hooks decided about a prompt. */
data class PromptHookResult(val blocked: Boolean = false, val message: String? = null, val context: String? = null)

/**
 * Claude Code style hooks on top of opencode, run by the app through [RemoteExec] in the chat's
 * project directory. Listens to the process-wide event stream, so hooks fire for every chat while
 * the app is alive, not only the one on screen:
 *
 *  * [HookEvent.FILE_EDITED] after `edit`/`write`/`patch` tools complete;
 *  * [HookEvent.TURN_END] when a session goes idle;
 *  * [HookEvent.BEFORE_TOOL] when the agent asks for a permission (works with "ask" rules);
 *  * [HookEvent.PROMPT_SUBMIT] is called by the chat before it sends ([beforePrompt]).
 *
 * Failures of hooks with `feedback` are sent back to the agent as a new prompt once the session is
 * idle, at most [MAX_AUTO_PROMPTS] times in a row, so a hook cannot loop the agent forever.
 */
class HookRunner(
    private val connection: ServerConnectionManager,
    private val repository: SessionRepository,
    private val settings: SettingsRepository,
    private val workbench: Workbench,
    private val exec: RemoteExec,
    private val scope: CoroutineScope,
) {

    private val _runs = MutableStateFlow<List<HookRun>>(emptyList())
    val runs: StateFlow<List<HookRun>> = _runs.asStateFlow()

    private val busy = ConcurrentHashMap.newKeySet<String>()
    private val handledParts = ConcurrentHashMap.newKeySet<String>()
    private val toolInputs = ConcurrentHashMap<String, String>()
    private val directories = ConcurrentHashMap<String, String>()
    private val pendingFeedback = ConcurrentHashMap<String, MutableList<String>>()
    private val autoPrompts = ConcurrentHashMap<String, Int>()

    fun start() {
        scope.launch { connection.events.collect(::onEvent) }
    }

    private fun hooks(event: HookEvent): List<Hook> = workbench.current.hooks.filter { it.enabled && it.event == event }

    private fun onEvent(event: OpencodeEvent) {
        when (event) {
            is OpencodeEvent.Status -> if (event.busy) busy += event.sessionId else turnEnded(event.sessionId)
            is OpencodeEvent.Idle -> turnEnded(event.sessionId)
            is OpencodeEvent.TextDelta -> busy += event.sessionId
            is OpencodeEvent.ToolCalled -> event.input?.let { toolInputs[event.toolId] = it }
            is OpencodeEvent.ToolSucceeded -> toolInputs.remove(event.toolId)?.let { input ->
                editedFiles(null, input).forEach { fileEdited(event.sessionId, it) }
            }
            is OpencodeEvent.PartUpserted -> {
                busy += event.sessionId
                val tool = (event.part as? MessagePart.Tool)?.call ?: return
                if (tool.status != ToolStatus.COMPLETED || !handledParts.add(event.part.id)) return
                editedFiles(tool.name, tool.input).forEach { fileEdited(event.sessionId, it) }
            }
            is OpencodeEvent.PermissionAsked -> beforeTool(event.request)
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ events

    private fun fileEdited(sessionId: String, path: String) {
        val matching = hooks(HookEvent.FILE_EDITED)
        if (matching.isEmpty()) return
        scope.launch {
            val directory = directory(sessionId)
            matching.filter { HookMatch.file(it.matcher, path, directory) }.forEach { hook ->
                val run = execute(hook, sessionId, directory, path, mapOf("FILE" to path))
                if (!run.ok && hook.feedback) queueFeedback(sessionId, hook, run)
            }
        }
    }

    private fun turnEnded(sessionId: String) {
        if (!busy.remove(sessionId)) return
        scope.launch {
            val turnHooks = hooks(HookEvent.TURN_END)
            if (turnHooks.isNotEmpty()) {
                val directory = directory(sessionId)
                turnHooks.forEach { hook ->
                    val run = execute(hook, sessionId, directory, null, emptyMap())
                    if (!run.ok && hook.feedback) queueFeedback(sessionId, hook, run)
                }
            }
            flushFeedback(sessionId)
        }
    }

    private fun beforeTool(request: PermissionRequest) {
        val matching = hooks(HookEvent.BEFORE_TOOL).filter { HookMatch.tool(it.matcher, request.action) }
        if (matching.isEmpty()) return
        scope.launch {
            val directory = directory(request.sessionId)
            val env = mapOf(
                "TOOL" to request.action,
                "TOOL_TITLE" to request.title,
                "TOOL_PATTERNS" to request.resources.joinToString("\n"),
            )
            var decision: PermissionDecision? = null
            for (hook in matching) {
                val run = execute(hook, request.sessionId, directory, request.action, env)
                when (run.exitCode) {
                    EXIT_BLOCK -> {
                        decision = PermissionDecision.REJECT
                        break
                    }
                    0 -> if (decision == null) decision = PermissionDecision.ONCE
                    else -> {
                        // Undecided: leave the question to the user.
                        decision = null
                        break
                    }
                }
            }
            decision?.let { repository.replyPermission(request.sessionId, request.id, it) }
        }
    }

    /** Runs prompt-submit hooks; exit 2 blocks the prompt, stdout of `feedback` hooks is added. */
    suspend fun beforePrompt(sessionId: String, directory: String?, prompt: String): PromptHookResult {
        autoPrompts.remove(sessionId)
        val matching = hooks(HookEvent.PROMPT_SUBMIT)
        if (matching.isEmpty()) return PromptHookResult()
        val dir = directory ?: directory(sessionId)
        val context = StringBuilder()
        for (hook in matching) {
            val run = execute(hook, sessionId, dir, null, mapOf("PROMPT" to prompt))
            if (run.exitCode == EXIT_BLOCK) return PromptHookResult(blocked = true, message = "${hook.name}: ${run.output.take(400)}")
            if (run.ok && hook.feedback && run.output.isNotBlank()) context.append(run.output.trim()).append("\n\n")
        }
        return PromptHookResult(context = context.toString().trim().ifBlank { null })
    }

    /** "Test" button on the Hooks screen. */
    suspend fun test(hook: Hook, directory: String?): HookRun =
        execute(hook, null, directory, "test", mapOf("FILE" to "", "PROMPT" to "test", "TOOL" to "test"))

    // ------------------------------------------------------------------ plumbing

    private suspend fun execute(hook: Hook, sessionId: String?, directory: String?, target: String?, env: Map<String, String>): HookRun {
        val started = System.currentTimeMillis()
        val command = HookMatch.substitute(hook.command, env)
        val fullEnv = env + listOfNotNull(sessionId?.let { "SESSION_ID" to it }, directory?.let { "PROJECT_DIR" to it }) +
            ("HOOK_EVENT" to hook.event.name.lowercase())
        val run = try {
            val result = exec.run(command, directory, hook.timeoutSeconds.coerceIn(1, 3600) * 1000L, fullEnv)
            HookRun(hook.name, hook.event, sessionId, target, result.exitCode, result.output, started, System.currentTimeMillis() - started, result.timedOut)
        } catch (e: ApiException) {
            HookRun(hook.name, hook.event, sessionId, target, null, e.message.orEmpty(), started, System.currentTimeMillis() - started)
        } catch (e: java.io.IOException) {
            HookRun(hook.name, hook.event, sessionId, target, null, e.message ?: e.javaClass.simpleName, started, System.currentTimeMillis() - started)
        }
        Log.i(TAG, "${hook.name} (${hook.event}) exit=${run.exitCode} in ${run.durationMs}ms")
        _runs.update { (listOf(run) + it).take(MAX_RUNS) }
        return run
    }

    private fun queueFeedback(sessionId: String, hook: Hook, run: HookRun) {
        val text = buildString {
            append("Hook \"").append(hook.name).append("\" failed")
            run.target?.let { append(" for ").append(it) }
            append(" (exit ").append(run.exitCode ?: "?").append(").\n\n```\n")
            append(run.output.takeLast(MAX_FEEDBACK_CHARS).trim().ifBlank { "(no output)" })
            append("\n```\n\nFix the problem.")
        }
        pendingFeedback.getOrPut(sessionId) { mutableListOf() }.add(text)
        // The turn may already be over (file hooks finish after the agent).
        if (sessionId !in busy) scope.launch { flushFeedback(sessionId) }
    }

    private suspend fun flushFeedback(sessionId: String) {
        val items = pendingFeedback.remove(sessionId)?.takeIf { it.isNotEmpty() } ?: return
        val count = (autoPrompts[sessionId] ?: 0) + 1
        if (count > MAX_AUTO_PROMPTS) {
            Log.w(TAG, "auto-prompt limit reached for $sessionId")
            return
        }
        autoPrompts[sessionId] = count
        val current = settings.current()
        val request = PromptRequest(
            text = items.distinct().joinToString("\n\n---\n\n"),
            model = current.model,
            agent = current.agent.ifBlank { null },
        )
        busy += sessionId
        if (repository.sendPrompt(sessionId, request) is ApiResult.Err) busy.remove(sessionId)
    }

    private suspend fun directory(sessionId: String): String? {
        directories[sessionId]?.let { return it }
        val dir = (repository.session(sessionId) as? ApiResult.Ok)?.value?.directory
            ?: (connection.state.value as? ai.opencode.android.data.model.ConnectionState.Connected)?.info?.directory
        dir?.let { directories[sessionId] = it }
        return dir
    }

    companion object {
        private const val TAG = "Hooks"
        const val EXIT_BLOCK = 2
        const val MAX_AUTO_PROMPTS = 3
        private const val MAX_RUNS = 40
        private const val MAX_FEEDBACK_CHARS = 4000
        private val EDIT_TOOLS = setOf("edit", "write", "multiedit", "patch", "apply_patch", "str_replace_editor")

        /** Paths a completed tool call wrote; [name] `null` means "decide from the input alone". */
        fun editedFiles(name: String?, input: String?): List<String> {
            if (input.isNullOrBlank()) return emptyList()
            if (name != null && name.lowercase() !in EDIT_TOOLS) return emptyList()
            // Tool cards keep a truncated rendering of large inputs; the path is near the top anyway.
            val json = runCatching { OpencodeJson.parseToJsonElement(input) as? JsonObject }.getOrNull()
                ?: return listOfNotNull(TRUNCATED_PATH.find(input)?.groupValues?.get(1)).takeIf { name != null }.orEmpty()
            val direct = json.str("filePath") ?: json.str("file_path") ?: json.str("path")
            if (direct != null && (name != null || json.containsKey("oldString") || json.containsKey("content") || json.containsKey("edits"))) {
                return listOf(direct)
            }
            val patch = json.str("patchText") ?: json.str("patch") ?: json.str("input") ?: return emptyList()
            return PATCH_FILE.findAll(patch).map { it.groupValues[2].trim() }.distinct().toList()
        }

        private val TRUNCATED_PATH = Regex("\"(?:filePath|file_path|path)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"")
        private val PATCH_FILE = Regex("(?m)^\\*\\*\\* (Update|Add) File: (.+)$")
    }
}

/** Pure matching and substitution helpers, unit-tested. */
object HookMatch {

    /** Glob on the path relative to [directory]; a pattern without `/` matches the file name. */
    fun file(pattern: String, path: String, directory: String?): Boolean {
        val glob = pattern.trim()
        if (glob.isEmpty() || glob == "*" || glob == "**") return true
        val relative = directory?.trimEnd('/')?.let { dir -> if (path.startsWith("$dir/")) path.removePrefix("$dir/") else path } ?: path
        val parts = if ('{' in glob) glob.split(Regex("\\s+")) else glob.split(',', ' ')
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.any { part ->
            val scoped = if ('/' in part) relative else relative.substringAfterLast('/')
            globRegex(part).matches(scoped)
        }
    }

    /** Case-insensitive regex on the permission type (`edit`, `bash`, `webfetch`, MCP tool ids). */
    fun tool(pattern: String, tool: String): Boolean {
        val source = pattern.trim()
        if (source.isEmpty() || source == "*") return true
        return runCatching { Regex(source, RegexOption.IGNORE_CASE).containsMatchIn(tool) }.getOrDefault(source.equals(tool, ignoreCase = true))
    }

    fun globRegex(glob: String): Regex {
        val out = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val ch = glob[i]
            when {
                ch == '*' && glob.startsWith("**/", i) -> {
                    out.append("(?:.*/)?")
                    i += 3
                    continue
                }
                ch == '*' && glob.startsWith("**", i) -> {
                    out.append(".*")
                    i += 2
                    continue
                }
                ch == '*' -> out.append("[^/]*")
                ch == '?' -> out.append("[^/]")
                ch == '{' -> out.append("(?:")
                ch == '}' -> out.append(")")
                ch == ',' && glob.lastIndexOf('{', i) > glob.lastIndexOf('}', i) -> out.append("|")
                else -> out.append(Regex.escape(ch.toString()))
            }
            i++
        }
        return Regex(out.append("$").toString())
    }

    /** `{file}`, `{prompt}`, `{tool}` in the command become shell-quoted values. */
    fun substitute(command: String, env: Map<String, String>): String {
        var text = command
        env.forEach { (key, value) -> text = text.replace("{${key.lowercase()}}", RemoteExec.quote(value)) }
        return text
    }
}
