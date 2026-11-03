package ai.opencode.android.ui.chat

import ai.opencode.android.R
import androidx.annotation.StringRes

/**
 * Slash commands the app runs itself: the opencode TUI's own commands (`/new`, `/models`,
 * `/undo`...) have no server endpoint, so without these they would reach the model as text.
 * Server commands (`/init`, `/review`, custom ones, MCP prompts, skills) are listed separately.
 */
enum class ClientCommand(
    val id: String,
    val aliases: List<String>,
    /** Argument placeholder shown after the name in the popup, `null` when it takes none. */
    val argHint: String?,
    @StringRes val description: Int,
) {
    NEW("new", listOf("clear"), null, R.string.cmd_new),
    SESSIONS("sessions", listOf("resume", "continue"), null, R.string.cmd_sessions),
    MODELS("models", listOf("model"), "[provider/model]", R.string.cmd_models),
    AGENT("agent", listOf("agents", "mode"), "[name]", R.string.cmd_agent),
    COMPACT("compact", listOf("summarize"), null, R.string.cmd_compact),
    CONTEXT("context", listOf("cost", "usage", "tokens"), "[128k | reset]", R.string.cmd_context),
    UNDO("undo", emptyList(), null, R.string.cmd_undo),
    REDO("redo", emptyList(), null, R.string.cmd_redo),
    FORK("fork", listOf("branch"), null, R.string.cmd_fork),
    RENAME("rename", listOf("title"), "<title>", R.string.cmd_rename),
    SHARE("share", emptyList(), null, R.string.cmd_share),
    UNSHARE("unshare", emptyList(), null, R.string.cmd_unshare),
    EXPORT("export", emptyList(), null, R.string.cmd_export),
    COPY("copy", emptyList(), null, R.string.cmd_copy),
    DETAILS("details", listOf("tools"), null, R.string.cmd_details),
    THINKING("thinking", listOf("reasoning"), null, R.string.cmd_thinking),
    SYSTEM("system", listOf("prompt"), "[name | off]", R.string.cmd_system),
    MCP("mcp", emptyList(), null, R.string.cmd_mcp),
    PERMISSIONS("permissions", listOf("allowed-tools"), null, R.string.cmd_permissions),
    AGENTS_CONFIG("subagents", listOf("agents-config"), null, R.string.cmd_subagents),
    TERMINAL("terminal", listOf("term"), null, R.string.cmd_terminal),
    DIFF("diff", listOf("changes"), null, R.string.cmd_diff),
    SETTINGS("settings", listOf("config"), null, R.string.cmd_settings),
    THEME("theme", listOf("themes", "appearance"), null, R.string.cmd_theme),
    STATUS("status", emptyList(), null, R.string.cmd_status),
    REWIND("rewind", listOf("checkpoints", "checkpoint"), null, R.string.cmd_rewind),
    GIT("git", listOf("vcs"), null, R.string.cmd_git),
    BACKGROUND("bg", listOf("background"), "<prompt>", R.string.cmd_bg),
    TASKS("tasks", listOf("jobs"), null, R.string.cmd_tasks),
    SNIPPETS("snippets", listOf("prompts", "saved"), null, R.string.cmd_snippets),
    HOOKS("hooks", emptyList(), null, R.string.cmd_hooks),
    STATS("stats", listOf("spending", "budget"), null, R.string.cmd_stats),
    ENV("env", listOf("environment", "install-tools"), null, R.string.cmd_env),
    HELP("help", listOf("?", "commands"), null, R.string.cmd_help),
    EXIT("exit", listOf("quit", "q"), null, R.string.cmd_exit),
    ;

    val names: List<String> get() = listOf(id) + aliases

    companion object {
        fun find(name: String): ClientCommand? {
            val lower = name.lowercase()
            return entries.firstOrNull { lower == it.id || lower in it.aliases }
        }
    }
}

/** Screens a command or menu item can open; mapped to routes by the nav host. */
enum class ChatDestination {
    SESSIONS, SETTINGS, CHAT_SETTINGS, AGENTS, MCP, PERMISSIONS, TERMINAL, DIFF, SYSTEM_PROMPTS, PROVIDERS,
    GIT, TASKS, SNIPPETS, HOOKS, STATS, ENV,
}

/** `/name args` split; `null` when [text] is not a command at all. */
data class ParsedCommand(val name: String, val arguments: String)

fun parseCommand(text: String): ParsedCommand? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("/") || trimmed.length < 2) return null
    val name = trimmed.substring(1).substringBefore(' ').substringBefore('\n').trim()
    if (name.isEmpty()) return null
    val arguments = trimmed.substring(1 + name.length).trim()
    return ParsedCommand(name, arguments)
}

/**
 * `/usr/bin/env python` or `/etc/hosts` typed as a prompt must not be mistaken for an unknown
 * command: anything with a slash or dot in the "name" is a path and goes to the model as text.
 */
fun looksLikePath(name: String): Boolean = name.contains('/') || name.contains('.') || name.contains('\\')

/** `128k`, `1m`, `200000`, `1.5M` → tokens; `null` when unparsable or not positive. */
fun parseTokenCount(raw: String): Long? {
    val text = raw.trim().lowercase().replace("_", "").replace(" ", "").replace(",", "")
    if (text.isEmpty()) return null
    val multiplier = when (text.last()) {
        'k' -> 1_000.0
        'm' -> 1_000_000.0
        else -> 1.0
    }
    val number = if (multiplier == 1.0) text else text.dropLast(1)
    val value = number.toDoubleOrNull() ?: return null
    val tokens = (value * multiplier).toLong()
    return tokens.takeIf { it in 1_000..100_000_000 }
}
