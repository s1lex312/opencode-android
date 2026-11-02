package ai.opencode.android.server

/**
 * Classifies a raw server log line: level, the port the server reports, whether the line proves
 * the server is up, and whether it looks fatal. Used by the supervisor to react to log output
 * (for example "EADDRINUSE" means an immediate restart would loop).
 */
object LogLineParser {

    enum class Level { INFO, WARN, ERROR }

    data class Parsed(
        val level: Level,
        val text: String,
        val port: Int? = null,
        val ready: Boolean = false,
        val fatal: Boolean = false,
        val exitCode: Int? = null,
    )

    private val PORT_PATTERN = Regex(""":(\d{2,5})\b""")
    private val READY_PATTERNS = listOf(
        Regex("""listening (?:on|at)\s+(?:https?://)?[\w.\-]+:(\d{2,5})""", RegexOption.IGNORE_CASE),
        Regex("""server (?:is )?(?:now )?listening""", RegexOption.IGNORE_CASE),
        Regex("""opencode server (?:started|ready|running)""", RegexOption.IGNORE_CASE),
        Regex("""http://(?:127\.0\.0\.1|localhost):(\d{2,5})""", RegexOption.IGNORE_CASE),
    )
    private val FATAL_PATTERNS = listOf(
        "eaddrinuse",
        "address already in use",
        "permission denied",
        "no such file or directory",
        "exec format error",
        "cannot execute",
        "proot error",
        "fatal:",
    )
    private val ERROR_PATTERNS = listOf("error", "exception", "traceback", "failed", "fatal", "panic")
    private val WARN_PATTERNS = listOf("warn", "deprecat")
    private val EXIT_CODE_PATTERN = Regex("""(?:exit(?:ed)?(?: with)?(?: code)?|code)[ =:](\d{1,3})""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Parsed {
        val text = raw.trimEnd()
        val lower = text.lowercase()

        var port: Int? = null
        var ready = false
        for (pattern in READY_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                ready = true
                port = match.groupValues.getOrNull(1)?.toIntOrNull() ?: port
                break
            }
        }
        if (port == null && ready) port = PORT_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val fatal = FATAL_PATTERNS.any { lower.contains(it) }
        val level = when {
            fatal -> Level.ERROR
            ERROR_PATTERNS.any { lower.contains(it) } -> Level.ERROR
            WARN_PATTERNS.any { lower.contains(it) } -> Level.WARN
            else -> Level.INFO
        }
        val exitCode = EXIT_CODE_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return Parsed(
            level = level,
            text = text,
            port = port,
            ready = ready,
            fatal = fatal,
            exitCode = exitCode,
        )
    }
}
