package ai.opencode.android.server

import ai.opencode.android.settings.LocalServerSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Experimental embedded-server supervisor.
 *
 * The app does **not** ship a proot binary or a rootfs. When a developer provisions them (see
 * `server/README.md`), this class can launch `opencode serve` inside a proot rootfs from a
 * foreground service, stream its logs and gate the UI on a health check.
 *
 * Design notes:
 *  * no root, no shell injection from user text (the command template is split by the shell we
 *    explicitly start inside the rootfs, and only `{port}`/`{rootfs}` are substituted);
 *  * the process is killed when the user stops it and when the service dies;
 *  * a watchdog polls the HTTP health endpoint, because "process is alive" does not mean
 *    "server is listening".
 */
class LocalServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(LocalServerState())
    val state: StateFlow<LocalServerState> = _state.asStateFlow()

    private val logBuffer = ArrayDeque<String>()

    private var process: Process? = null
    private var logJob: Job? = null
    private var healthJob: Job? = null
    private var startJob: Job? = null

    fun defaultRootfsDirectory(): File = File(context.filesDir, "rootfs")

    fun defaultProotPath(): File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    fun start(settings: LocalServerSettings) {
        if (_state.value.running) return
        startJob?.cancel()
        startJob = scope.launch(Dispatchers.IO) { startInternal(settings) }
    }

    fun stop() {
        startJob?.cancel()
        healthJob?.cancel()
        val current = process
        _state.value = _state.value.copy(phase = LocalServerState.Phase.STOPPING)
        scope.launch(Dispatchers.IO) {
            runCatching {
                current?.destroy()
                if (current?.waitFor(5, TimeUnit.SECONDS) == false) current.destroyForcibly()
            }
            process = null
            logJob?.cancel()
            appendLog("— server stopped —")
            _state.value = LocalServerState(
                phase = LocalServerState.Phase.STOPPED,
                logs = snapshotLogs(),
            )
        }
    }

    fun clearLogs() {
        logBuffer.clear()
        _state.value = _state.value.copy(logs = emptyList())
    }

    private suspend fun startInternal(settings: LocalServerSettings) {
        val rootfs = File(settings.rootfsPath.ifBlank { defaultRootfsDirectory().absolutePath })
        val proot = resolveProot(settings)
        val port = settings.port.takeIf { it in 1..65535 } ?: 4096

        if (proot == null) {
            fail(
                "proot binary not found. Push an arm64 proot to " +
                    "${defaultProotPath().absolutePath} (or set a custom path), see server/README.md.",
            )
            return
        }
        if (!rootfs.isDirectory) {
            fail(
                "Rootfs directory not found: ${rootfs.absolutePath}. " +
                    "Provision it with server/scripts/bootstrap-rootfs.sh.",
            )
            return
        }
        if (process?.isAlive == true) return

        _state.value = LocalServerState(phase = LocalServerState.Phase.STARTING, logs = snapshotLogs())
        appendLog("Starting opencode inside proot (port $port)…")

        val command = settings.serverCommand
            .ifBlank { "opencode serve --hostname 127.0.0.1 --port {port}" }
            .replace("{port}", port.toString())
            .replace("{rootfs}", rootfs.absolutePath)

        val args = listOf(
            proot.absolutePath,
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-w", "/root",
            "/usr/bin/env",
            "HOME=/root",
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "TERM=dumb",
            "/bin/sh",
            "-lc",
            command,
        )

        try {
            val builder = ProcessBuilder(args)
                .redirectErrorStream(true)
                .directory(rootfs)
            val started = builder.start()
            process = started
            _state.value = _state.value.copy(pid = runCatching { pidOf(started) }.getOrNull())
            logJob = scope.launch(Dispatchers.IO) { pumpLogs(started) }
            waitForHealth(port, started)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start local server: ${t.javaClass.simpleName}")
            fail(t.message ?: "Failed to start proot")
        }
    }

    private suspend fun waitForHealth(port: Int, started: Process) {
        val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(1_500)
            if (!started.isAlive) {
                fail("opencode exited with code ${runCatching { started.exitValue() }.getOrDefault(-1)}. Check the log.")
                return
            }
            if (isHealthy(port)) {
                appendLog("Health check passed on 127.0.0.1:$port")
                _state.value = _state.value.copy(
                    phase = LocalServerState.Phase.READY,
                    healthy = true,
                    message = "Listening on 127.0.0.1:$port",
                    logs = snapshotLogs(),
                )
                startHealthWatchdog(port, started)
                return
            }
        }
        fail("Timed out waiting for the server to answer on 127.0.0.1:$port")
    }

    private fun startHealthWatchdog(port: Int, started: Process) {
        healthJob?.cancel()
        healthJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(10_000)
                if (!started.isAlive) {
                    fail("opencode process exited")
                    return@launch
                }
                val healthy = isHealthy(port)
                if (_state.value.healthy != healthy) {
                    _state.value = _state.value.copy(healthy = healthy, logs = snapshotLogs())
                }
            }
        }
    }

    suspend fun isHealthy(port: Int): Boolean = withContext(Dispatchers.IO) {
        val candidates = listOf("/api/info", "/config")
        candidates.any { path ->
            runCatching {
                val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2_500
                    readTimeout = 2_500
                    requestMethod = "GET"
                }
                try {
                    val code = connection.responseCode
                    // 401 means the server is up but wants credentials: still healthy.
                    code in 200..499
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(false)
        }
    }

    private fun resolveProot(settings: LocalServerSettings): File? {
        val explicit = settings.prootPath.takeIf { it.isNotBlank() }?.let(::File)
        val candidates = listOfNotNull(
            explicit,
            defaultProotPath(),
            File(context.filesDir, "proot/proot"),
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }
            ?: candidates.firstOrNull { it.isFile }
    }

    private suspend fun pumpLogs(started: Process) {
        runCatching {
            started.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> appendLog(line) }
            }
        }
        appendLog("— log stream ended —")
    }

    private fun appendLog(line: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(line.take(2_000))
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        }
        _state.value = _state.value.copy(logs = snapshotLogs())
    }

    private fun snapshotLogs(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

    private fun fail(message: String) {
        appendLog("ERROR: $message")
        _state.value = LocalServerState(
            phase = LocalServerState.Phase.FAILED,
            message = message,
            logs = snapshotLogs(),
            pid = null,
            healthy = false,
        )
        process?.let { runCatching { it.destroy() } }
        process = null
    }

    private fun pidOf(process: Process): Int? = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    }.getOrNull()

    private companion object {
        const val TAG = "LocalServer"
        const val MAX_LOG_LINES = 500
        const val HEALTH_TIMEOUT_MS = 120_000L
    }
}
