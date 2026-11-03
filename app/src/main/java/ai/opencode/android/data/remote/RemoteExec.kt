package ai.opencode.android.data.remote

import ai.opencode.android.data.repo.ServerConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs a non-interactive shell script on the opencode server and returns its output and exit code.
 *
 * opencode exposes no "exec" endpoint, but its PTY API (`POST /pty` with `command`/`args` plus the
 * WebSocket) runs arbitrary programs for the TUI's terminal. The script is wrapped so that:
 *
 *  * it waits for one line on stdin before starting, so no output is lost while the socket connects;
 *  * stderr is merged into stdout (a PTY has one stream anyway);
 *  * the exit status is printed behind a random marker, so it is found even when the server keeps
 *    the PTY open after the process exits.
 *
 * Works the same for the in-app server (inside proot) and remote ones; git, apt and hooks use it.
 */
class RemoteExec(private val connection: ServerConnectionManager) {

    data class Result(val exitCode: Int?, val output: String, val timedOut: Boolean = false) {
        val ok: Boolean get() = exitCode == 0
    }

    suspend fun run(
        script: String,
        cwd: String?,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        env: Map<String, String> = emptyMap(),
        onOutput: ((String) -> Unit)? = null,
    ): Result {
        val api = connection.api ?: throw ApiException(null, "Not connected to an opencode server")
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val pty = api.createPty(cwd, SHELL, listOf("-c", wrap(script, token)), BASE_ENV + env)
        val done = CompletableDeferred<Unit>()
        val collector = ExecOutput(token)
        var socket: WebSocket? = null
        try {
            val client = api.http.sseClient.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
            val request = okhttp3.Request.Builder().url(api.http.url(api.ptyConnectPath(pty.id))).build()
            socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Releases the `read` gate in the wrapper.
                        webSocket.send("\n")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = feed(text)

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        if (bytes.size > 0 && bytes[0] == 0.toByte()) return
                        feed(bytes.utf8())
                    }

                    private fun feed(chunk: String) {
                        val fresh = synchronized(collector) { collector.append(chunk) }
                        if (fresh.isNotEmpty()) onOutput?.invoke(fresh)
                        if (collector.exitCode != null) done.complete(Unit)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        done.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        synchronized(collector) { collector.failure = response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName }
                        done.complete(Unit)
                    }
                },
            )
            val finished = withTimeoutOrNull(timeoutMs) { done.await() } != null
            val output = synchronized(collector) { collector.text() }
            val failure = synchronized(collector) { collector.failure }
            return when {
                collector.exitCode != null -> Result(collector.exitCode, output)
                !finished -> Result(null, output, timedOut = true)
                failure != null && output.isBlank() -> throw ApiException(null, failure)
                else -> Result(null, output)
            }
        } finally {
            socket?.close(1000, null)
            withContext(NonCancellable + Dispatchers.IO) { runCatching { api.removePty(pty.id) } }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 120_000L
        private const val SHELL = "/bin/sh"

        /** Plain, uncoloured, never prompting output. */
        val BASE_ENV = mapOf(
            "TERM" to "dumb",
            "NO_COLOR" to "1",
            "PAGER" to "cat",
            "GIT_PAGER" to "cat",
            "GIT_TERMINAL_PROMPT" to "0",
            "DEBIAN_FRONTEND" to "noninteractive",
            "LC_ALL" to "C.UTF-8",
        )

        fun marker(token: String): String = "__OCX_${token}_"

        /** The gate, the script in a subshell, then `marker<status>__`. */
        fun wrap(script: String, token: String): String =
            "IFS= read -r _oc_gate; ( $script\n) 2>&1; printf '\\n${marker(token)}%d__\\n' \"\$?\""

        /** Quotes [value] for POSIX sh. */
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}

/**
 * Accumulates PTY output: strips terminal escapes and the echoed gate newline, normalises line
 * endings and recognises the exit marker. Pure, so the parsing is unit-tested without a server.
 */
class ExecOutput(token: String) {
    private val raw = StringBuilder()
    private var emitted = 0
    private val markerRegex = Regex("\\n?" + Regex.escape(RemoteExec.marker(token)) + "(\\d+)__")

    var exitCode: Int? = null
        private set

    var failure: String? = null

    /** Adds a chunk; returns the newly visible clean text (without the marker). */
    fun append(chunk: String): String {
        if (exitCode != null) return ""
        raw.append(chunk)
        val clean = clean(raw.toString())
        val match = markerRegex.find(clean)
        val visible = if (match != null) {
            exitCode = match.groupValues[1].toIntOrNull()
            clean.substring(0, match.range.first)
        } else {
            // Hold back a possible partial marker at the end.
            val cut = clean.lastIndexOf("\n__OCX_").takeIf { it >= 0 } ?: clean.lastIndexOf("__OCX_").takeIf { it >= 0 } ?: clean.length
            clean.substring(0, cut)
        }
        val fresh = if (visible.length > emitted) visible.substring(emitted) else ""
        emitted = maxOf(emitted, visible.length)
        return fresh
    }

    fun text(): String {
        val clean = clean(raw.toString())
        val match = markerRegex.find(clean)
        return (if (match != null) clean.substring(0, match.range.first) else clean).trimEnd()
    }

    companion object {
        private val ANSI = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])")

        fun clean(text: String): String {
            val stripped = ANSI.replace(text, "").replace("\r\n", "\n")
            // A bare CR (progress bars) rewrites the line: keep what was written last.
            val lines = stripped.split('\n').map { line -> if ('\r' in line) line.split('\r').lastOrNull { it.isNotEmpty() }.orEmpty() else line }
            // The PTY echoes the gate's newline first.
            return lines.joinToString("\n").removePrefix("\n")
        }
    }
}
