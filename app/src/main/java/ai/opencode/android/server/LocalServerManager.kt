package ai.opencode.android.server

import ai.opencode.android.security.SecretStore
import ai.opencode.android.server.provision.EnvProvisioner
import ai.opencode.android.settings.LocalServerSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Supervises the in-app `opencode serve` process inside proot.
 *
 * Responsibilities:
 *  * start `proot -0 -r <rootfs> ... /bin/sh -lc "opencode serve --hostname 127.0.0.1 --port N"`;
 *  * pass the generated server password through the environment (never through argv, never logged);
 *  * wait for the HTTP health check (`GET /`), watch it while running and restart with exponential
 *    backoff (bounded by [LocalServerSettings.maxRestarts]);
 *  * mirror stdout/stderr into the UI ring buffer and into a rotating log file.
 *
 * No root is required: proot is a user-space `chroot`-like runtime and the process runs under the
 * app's own UID.
 */
class LocalServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val provisioner: EnvProvisioner,
    private val secretStore: SecretStore,
) {

    private val _state = MutableStateFlow(LocalServerState())
    val state: StateFlow<LocalServerState> = _state.asStateFlow()

    private val logBuffer = ArrayDeque<String>()
    private val logStore = ServerLogStore(provisioner.paths().logFile)

    private var supervisorJob: Job? = null
    private var process: Process? = null
    private var logPumpJob: Job? = null

    @Volatile
    private var userStopped = false

    val logFilePath: String get() = logStore.path()

    /** Starts the server (idempotent). Never throws: failures land in [state]. */
    fun start(settings: LocalServerSettings) {
        if (_state.value.running) return
        userStopped = false
        supervisorJob?.cancel()
        supervisorJob = scope.launch(Dispatchers.IO) { supervise(settings) }
    }

    /** Starts and waits until the health check passes (or the timeout expires). */
    suspend fun startAndAwaitReady(settings: LocalServerSettings, timeoutMs: Long = 180_000): LocalReadyResult {
        if (_state.value.phase == LocalServerState.Phase.FAILED) {
            _state.update { it.copy(phase = LocalServerState.Phase.STOPPED) }
        }
        if (!provisioner.isInstalled()) {
            return LocalReadyResult.NotInstalled("The opencode environment is not installed yet")
        }
        start(settings)
        val ready = withTimeoutOrNull(timeoutMs) {
            state.first { it.phase == LocalServerState.Phase.READY || it.phase == LocalServerState.Phase.FAILED }
        } ?: return LocalReadyResult.Timeout
        return when (ready.phase) {
            LocalServerState.Phase.READY -> LocalReadyResult.Ready
            else -> LocalReadyResult.Failed(ready.message ?: "Local server failed to start")
        }
    }

    fun stop() {
        userStopped = true
        logPumpJob?.cancel()
        supervisorJob?.cancel()
        supervisorJob = null
        val current = process
        process = null
        _state.update { it.copy(phase = LocalServerState.Phase.STOPPING, healthy = false) }
        scope.launch(Dispatchers.IO) {
            runCatching {
                current?.destroy()
                if (current?.waitFor(5, TimeUnit.SECONDS) == false) current.destroyForcibly()
            }
            appendLog("— server stopped —")
            logStore.appendNote("server stopped by user")
            _state.update {
                LocalServerState(
                    phase = LocalServerState.Phase.STOPPED,
                    logs = snapshotLogs(),
                    port = it.port,
                    envVersion = provisioner.installedVersion(),
                    logFilePath = logStore.path(),
                )
            }
        }
    }

    fun clearLogs() {
        synchronized(logBuffer) { logBuffer.clear() }
        logStore.clear()
        _state.update { it.copy(logs = emptyList()) }
    }

    fun logLines(maxLines: Int = 400): List<String> = logStore.tail(maxLines)

    suspend fun isHealthy(port: Int): Boolean = withContext(Dispatchers.IO) {
        HealthProbe(LocalHttpPinger(port)).check().healthy
    }

    // ------------------------------------------------------------------ supervision

    private suspend fun supervise(settings: LocalServerSettings) {
        val policy = RestartPolicy(maxRestarts = LocalServerSettings.MAX_RESTARTS)
        val port = settings.port.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        var restarts = 0

        _state.value = LocalServerState(
            phase = LocalServerState.Phase.STARTING,
            port = port,
            logs = snapshotLogs(),
            envVersion = provisioner.installedVersion(),
            logFilePath = logStore.path(),
        )

        val installed = provisioner.installedManifest()
        if (installed == null) {
            fail("The opencode environment is not installed. Open Setup and install it.")
            return
        }
        if (installed.abi.isNotBlank() && !installed.abi.contains("arm64")) {
            appendLog("WARNING: environment ABI is ${installed.abi}, arm64-v8a is expected on most devices")
        }

        try {
            while (currentCoroutineContext().isActive) {
                val started = launchProcess(settings, port) ?: return
                val ready = awaitReady(started, port, settings)
                if (!ready) {
                    val code = exitCodeOf(started)
                    destroy(started)
                    if (!currentCoroutineContext().isActive) return
                    if (!policy.shouldRestart(restarts)) {
                        fail(
                            "Server did not become healthy" +
                                (code?.let { " (exit code $it)" } ?: "") +
                                " after ${restarts + 1} attempt(s). Check the log.",
                        )
                        return
                    }
                    restarts += 1
                    val delayMs = policy.delayFor(restarts)
                    _state.update {
                        it.copy(
                            phase = LocalServerState.Phase.STARTING,
                            healthy = false,
                            restarts = restarts,
                            message = "Restarting (attempt $restarts/${policy.maxRestarts}) in ${delayMs / 1000}s",
                        )
                    }
                    appendLog("Restarting after failure (attempt $restarts/${policy.maxRestarts}, ${delayMs}ms)")
                    delay(delayMs)
                    continue
                }

                // Healthy: watch until it dies or stops answering.
                val reason = watch(started, port)
                destroy(started)
                if (!currentCoroutineContext().isActive || userStopped) return

                when (reason) {
                    StopReason.Exited -> {
                        appendLog("opencode exited${exitCodeOf(started)?.let { " with code $it" } ?: ""}")
                    }

                    StopReason.Unhealthy -> appendLog("Health check failed repeatedly; restarting the server")
                }

                if (!policy.shouldRestart(restarts)) {
                    fail("Server stopped ${restarts + 1} time(s); giving up. Check the log and restart manually.")
                    return
                }
                restarts += 1
                val delayMs = policy.delayFor(restarts)
                _state.update {
                    it.copy(
                        phase = LocalServerState.Phase.STARTING,
                        healthy = false,
                        restarts = restarts,
                        message = "Restarting (attempt $restarts/${policy.maxRestarts}) in ${delayMs / 1000}s",
                    )
                }
                appendLog("Restarting (attempt $restarts/${policy.maxRestarts}, ${delayMs}ms)")
                delay(delayMs)
            }
        } finally {
            destroy(process)
            process = null
            logPumpJob?.cancel()
        }
    }

    private fun launchProcess(settings: LocalServerSettings, port: Int): Process? {
        val paths = provisioner.paths()
        val rootfs = paths.rootfsDir
        // Android 10+ forbids executing files from the app's data dir, so a Full install runs
        // `libproot.so` from nativeLibraryDir; the extracted copy stays as a fallback.
        val proot = paths.prootBinary
        if (proot == null || !proot.isFile) {
            fail("proot binary is missing from the environment. Reinstall it from Setup.")
            return null
        }
        if (!rootfs.isDirectory) {
            fail("rootfs is missing from the environment. Reinstall it from Setup.")
            return null
        }

        val command = settings.serverCommand
            .ifBlank { DEFAULT_COMMAND }
            .replace("{port}", port.toString())
            .replace("{rootfs}", rootfs.absolutePath)

        val resolv = ensureResolvConf()
        val args = listOf(
            proot.absolutePath,
            "-0",
            "--kill-on-exit",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${resolv.absolutePath}:/etc/resolv.conf",
            "-w", "/root",
            "/usr/bin/env",
            "HOME=/root",
            "PATH=/usr/local/bin:/usr/bin:/bin",
            "TERM=dumb",
            "LANG=C.UTF-8",
            "/bin/sh",
            "-lc",
            command,
        )

        return try {
            val builder = ProcessBuilder(args)
                .redirectErrorStream(true)
                .directory(rootfs)
            // The password travels in the environment of the child process only: it is not part of
            // argv (so it never shows up in `ps`), and it is never written to the log.
            secretStore.localPassword()?.takeIf { it.isNotBlank() }?.let { password ->
                builder.environment()["OPENCODE_PASSWORD"] = password
                builder.environment()["OPENCODE_SERVER_PASSWORD"] = password
            }
            // proot extracts (and execs) its loader at runtime, so give it a writable scratch dir.
            val prootTmp = paths.tmpDir.apply { mkdirs() }
            builder.environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
            // Android kernels commonly refuse proot's seccomp acceleration; the syscall path is fine.
            builder.environment()["PROOT_NO_SECCOMP"] = "1"
            // The loader is a binary too: if the build shipped one, it sits next to libproot.so.
            paths.nativeProotLoader?.let { loader ->
                builder.environment()["PROOT_LOADER"] = loader.absolutePath
            }
            paths.nativeProotLoader32?.let { loader ->
                builder.environment()["PROOT_LOADER_32"] = loader.absolutePath
            }
            // Dynamically linked proot (Termux/bionic build) needs its libs: nativeLibraryDir first,
            // then the copy extracted from the bundle.
            val libDirs = paths.prootLibraryDirs().map { it.absolutePath }
            if (libDirs.isNotEmpty()) {
                builder.environment()["LD_LIBRARY_PATH"] = libDirs.joinToString(":")
            }
            val origin = if (proot == paths.nativeProot) "packaged in the APK" else "app storage"
            appendLog("Runtime: proot=${proot.absolutePath} ($origin)")
            val started = builder.start()
            process = started
            appendLog("Started proot (pid ${runCatching { pidOf(started) }.getOrNull() ?: -1}), port $port")
            logStore.appendNote("server process started, port=$port, env=${provisioner.installedVersion()}")
            _state.update {
                it.copy(pid = runCatching { pidOf(started) }.getOrNull(), port = port, phase = LocalServerState.Phase.STARTING)
            }
            logPumpJob?.cancel()
            logPumpJob = scope.launch(Dispatchers.IO) { pumpLogs(started) }
            started
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start local server: ${t.javaClass.simpleName}")
            fail("Failed to start proot: ${t.message ?: t.javaClass.simpleName}")
            null
        }
    }

    private suspend fun awaitReady(started: Process, port: Int, settings: LocalServerSettings): Boolean {
        val probe = HealthProbe(LocalHttpPinger(port))
        val deadline = System.currentTimeMillis() + HEALTH_TIMEOUT_MS
        var announced = false
        while (System.currentTimeMillis() < deadline) {
            if (!started.isAlive) return false
            val result = probe.check()
            if (result.healthy) {
                appendLog("Health check passed: ${result.path} → HTTP ${result.code}")
                logStore.appendNote("healthy on ${result.path} (HTTP ${result.code})")
                _state.update {
                    it.copy(
                        phase = LocalServerState.Phase.READY,
                        healthy = true,
                        port = port,
                        message = "Listening on 127.0.0.1:$port",
                        restarts = 0,
                    )
                }
                return true
            }
            if (!announced) {
                announced = true
                appendLog("Waiting for the server to answer on $port (${settings.serverCommand})")
            }
            delay(1_000)
        }
        return false
    }

    private suspend fun watch(started: Process, port: Int): StopReason {
        val probe = HealthProbe(LocalHttpPinger(port))
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            delay(5_000)
            if (!started.isAlive) return StopReason.Exited
            val healthy = probe.check().healthy
            if (healthy) {
                consecutiveFailures = 0
                if (!_state.value.healthy) _state.update { it.copy(healthy = true) }
            } else {
                consecutiveFailures += 1
                _state.update { it.copy(healthy = false) }
                if (consecutiveFailures >= MAX_HEALTH_FAILURES) return StopReason.Unhealthy
            }
        }
        return StopReason.Exited
    }

    private fun ensureResolvConf(): File {
        val file = File(provisioner.paths().root, "resolv.conf")
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            file.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        return file
    }

    private suspend fun pumpLogs(started: Process) {
        runCatching {
            started.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    appendLog(line)
                    val parsed = LogLineParser.parse(line)
                    if (parsed.fatal) {
                        logStore.appendNote("fatal log line detected: ${parsed.text.take(180)}")
                    }
                }
            }
        }
        appendLog("— log stream ended —")
    }

    private fun appendLog(line: String) {
        val text = line.take(2_000)
        synchronized(logBuffer) {
            logBuffer.addLast(text)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        }
        logStore.append(text)
        _state.update { it.copy(logs = snapshotLogs()) }
    }

    private fun snapshotLogs(): List<String> = synchronized(logBuffer) { logBuffer.toList() }

    private fun destroy(started: Process?) {
        if (started == null) return
        runCatching {
            started.destroy()
            if (!started.waitFor(5, TimeUnit.SECONDS)) started.destroyForcibly()
        }
    }

    private fun exitCodeOf(started: Process): Int? =
        runCatching { if (started.isAlive) null else started.exitValue() }.getOrNull()

    private fun fail(message: String) {
        appendLog("ERROR: $message")
        logStore.appendNote("ERROR: $message")
        process?.let { destroy(it) }
        process = null
        _state.update {
            LocalServerState(
                phase = LocalServerState.Phase.FAILED,
                message = message,
                logs = snapshotLogs(),
                pid = null,
                healthy = false,
                restarts = it.restarts,
                port = it.port,
                envVersion = provisioner.installedVersion(),
                logFilePath = logStore.path(),
            )
        }
    }

    private fun pidOf(process: Process): Int? = runCatching {
        val field = process.javaClass.getDeclaredField("pid")
        field.isAccessible = true
        field.getInt(process)
    }.getOrNull()

    private sealed interface StopReason {
        data object Exited : StopReason

        data object Unhealthy : StopReason
    }

    private companion object {
        const val TAG = "LocalServer"
        const val MAX_LOG_LINES = 500
        const val HEALTH_TIMEOUT_MS = 180_000L
        const val MAX_HEALTH_FAILURES = 3
        const val DEFAULT_PORT = 4096
        const val DEFAULT_COMMAND = "opencode serve --hostname 127.0.0.1 --port {port}"
    }
}
