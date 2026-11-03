package ai.opencode.android.data.mcp

import ai.opencode.android.data.remote.RemoteExec
import ai.opencode.android.server.GuestEnvironment

/**
 * Downloads what a local MCP server's command needs before opencode connects to it. `npx -y pkg`
 * and `uvx pkg` fetch and install the package on first start, which takes most of a minute on a
 * phone and runs into opencode's connect timeout; prepared beforehand, the connect takes seconds.
 *
 * The script installs into the same caches the real command uses (npx's `_npx` directory keyed by
 * the package spec, uv's tool cache) and runs nothing from the package itself.
 */
object McpWarmup {

    /** Shell script preparing [command], or null when it is not an npx/uvx launch. */
    fun script(command: List<String>): String? {
        val program = command.firstOrNull()?.substringAfterLast('/') ?: return null
        val prepare = when (program) {
            "npx" -> npx(command.drop(1))
            "uvx" -> uvx(command.drop(1))
            else -> null
        } ?: return null
        return buildString {
            append(GuestEnvironment.SHELL_UNSET).append('\n')
            append("command -v ").append(program).append(" >/dev/null 2>&1 || { echo \"").append(program)
                .append(" is not installed\"; exit 127; }\n")
            append(prepare).append(" </dev/null\n")
        }
    }

    private fun npx(args: List<String>): String? {
        val packages = mutableListOf<String>()
        var positional: String? = null
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--" -> break
                arg == "-p" || arg == "--package" -> {
                    args.getOrNull(index + 1)?.let(packages::add)
                    index++
                }
                arg.startsWith("--package=") -> packages += arg.substringAfter('=')
                arg.startsWith("-") -> Unit
                else -> {
                    positional = arg
                    break
                }
            }
            index++
        }
        // With --package the positional word is a binary name, not something to install.
        val specs = packages.ifEmpty { listOfNotNull(positional) }
        if (specs.isEmpty()) return null
        return "npx --yes " + specs.joinToString(" ") { "--package=${quote(it)}" } + " -- node -e 0"
    }

    private fun uvx(args: List<String>): String? {
        val passthrough = mutableListOf<String>()
        var from: String? = null
        var positional: String? = null
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--from" -> {
                    from = args.getOrNull(index + 1)
                    index++
                }
                arg.startsWith("--from=") -> from = arg.substringAfter('=')
                arg in UV_VALUE_OPTIONS -> {
                    args.getOrNull(index + 1)?.let { passthrough += listOf(arg, it) }
                    index++
                }
                UV_VALUE_OPTIONS.any { arg.startsWith("$it=") } -> passthrough += arg
                arg.startsWith("-") -> Unit
                else -> {
                    positional = arg
                    break
                }
            }
            index++
        }
        val spec = from ?: positional ?: return null
        // `python` exists in every tool environment, so this builds the environment and exits.
        return "uvx " + (passthrough + listOf("--from", spec)).joinToString(" ") { quote(it) } + " python -c pass"
    }

    private val UV_VALUE_OPTIONS = setOf("--with", "--python", "-p", "--index-url", "--extra-index-url", "--index")

    private fun quote(value: String): String =
        if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "@/._-=:+^~" }) value else "'" + value.replace("'", "'\\''") + "'"

    /**
     * Runs the preparation for every local server in [commands] (name → argv). Returns a warning
     * per server that could not be prepared; the server is still saved and opencode retries the
     * download itself when it connects.
     */
    suspend fun prepare(
        exec: RemoteExec,
        commands: Map<String, List<String>>,
        onServer: (String) -> Unit = {},
    ): List<String> {
        val warnings = mutableListOf<String>()
        commands.forEach { (name, command) ->
            val script = script(command) ?: return@forEach
            onServer(name)
            val result = runCatching { exec.run(script, null, TIMEOUT_MS) }.getOrElse {
                warnings += "mcp $name: packages not downloaded in advance (${it.message ?: it.javaClass.simpleName})"
                return@forEach
            }
            when {
                result.timedOut -> warnings += "mcp $name: downloading packages took over ${TIMEOUT_MS / 60_000} min"
                result.exitCode == 127 -> warnings += "mcp $name: ${command.first()} is not installed (Settings → Environment tools)"
                !result.ok -> warnings += "mcp $name: packages not downloaded in advance: " +
                    result.output.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(160)
            }
        }
        return warnings
    }

    const val TIMEOUT_MS = 10 * 60_000L
}
