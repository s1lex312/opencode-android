package ai.opencode.android.ui.tools

import ai.opencode.android.data.hooks.HookRun
import ai.opencode.android.data.hooks.HookRunner
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.remote.ApiException
import ai.opencode.android.data.remote.RemoteExec
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.stats.StatsState
import ai.opencode.android.data.stats.UsageStats
import ai.opencode.android.data.tasks.BackgroundTasks
import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.GuestEnvironment
import ai.opencode.android.settings.BackgroundTask
import ai.opencode.android.settings.BudgetSettings
import ai.opencode.android.settings.Hook
import ai.opencode.android.settings.Snippet
import ai.opencode.android.settings.Workbench
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------- tasks

class TasksViewModel(private val tasks: BackgroundTasks) : ViewModel() {

    val list: StateFlow<List<BackgroundTask>> = tasks.tasks

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun launch(prompt: String, onStarted: () -> Unit) {
        if (prompt.isBlank() || _starting.value) return
        _starting.value = true
        _error.value = null
        viewModelScope.launch {
            when (val result = tasks.launch(prompt.trim())) {
                is ApiResult.Ok -> onStarted()
                is ApiResult.Err -> _error.value = result.message
            }
            _starting.value = false
        }
    }

    fun remove(id: String) = viewModelScope.launch { tasks.remove(id) }

    fun clearFinished() = viewModelScope.launch { tasks.clearFinished() }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { TasksViewModel(container.backgroundTasks) }
        }
    }
}

// ---------------------------------------------------------------------------------- snippets & hooks

class WorkbenchViewModel(private val workbench: Workbench, private val hookRunner: HookRunner, private val connection: ServerConnectionManager) : ViewModel() {

    val snippets: StateFlow<List<Snippet>> = workbench.data.map { it.snippets }
        .stateIn(viewModelScope, SharingStarted.Eagerly, workbench.current.snippets)

    val hooks: StateFlow<List<Hook>> = workbench.data.map { it.hooks }
        .stateIn(viewModelScope, SharingStarted.Eagerly, workbench.current.hooks)

    val runs: StateFlow<List<HookRun>> = hookRunner.runs

    private val _testing = MutableStateFlow<String?>(null)

    /** Id of the hook whose test run is in flight. */
    val testing: StateFlow<String?> = _testing.asStateFlow()

    fun saveSnippet(snippet: Snippet) = viewModelScope.launch {
        workbench.update { data ->
            val others = data.snippets.filterNot { it.id == snippet.id || it.name == snippet.name }
            data.copy(snippets = (others + snippet).sortedBy { it.name })
        }
    }

    fun deleteSnippet(id: String) = viewModelScope.launch {
        workbench.update { data -> data.copy(snippets = data.snippets.filterNot { it.id == id }) }
    }

    fun saveHook(hook: Hook) = viewModelScope.launch {
        workbench.update { data ->
            val index = data.hooks.indexOfFirst { it.id == hook.id }
            data.copy(hooks = if (index < 0) data.hooks + hook else data.hooks.toMutableList().also { it[index] = hook })
        }
    }

    fun deleteHook(id: String) = viewModelScope.launch {
        workbench.update { data -> data.copy(hooks = data.hooks.filterNot { it.id == id }) }
    }

    fun setHookEnabled(hook: Hook, enabled: Boolean) = saveHook(hook.copy(enabled = enabled))

    fun test(hook: Hook) {
        if (_testing.value != null) return
        _testing.value = hook.id
        viewModelScope.launch {
            val directory = (connection.state.value as? ConnectionState.Connected)?.info?.directory
            hookRunner.test(hook, directory)
            _testing.value = null
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { WorkbenchViewModel(container.workbench, container.hookRunner, container.connectionManager) }
        }
    }
}

// ---------------------------------------------------------------------------------- stats

class StatsViewModel(private val stats: UsageStats, private val workbench: Workbench) : ViewModel() {

    val state: StateFlow<StatsState> = stats.state

    val budget: StateFlow<BudgetSettings> = workbench.data.map { it.budget }
        .stateIn(viewModelScope, SharingStarted.Eagerly, workbench.current.budget)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { stats.refresh() }
    }

    fun setBudget(transform: (BudgetSettings) -> BudgetSettings) = viewModelScope.launch {
        workbench.update { it.copy(budget = transform(it.budget)) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { StatsViewModel(container.usageStats, container.workbench) }
        }
    }
}

// ---------------------------------------------------------------------------------- environment

/** A tool the local MCP servers need. */
enum class EnvTool(val binary: String) { NODE("node"), NPM("npm"), NPX("npx"), PYTHON("python3"), PIP("pip3"), UV("uv"), UVX("uvx"), GIT("git"), CURL("curl") }

/** What can be installed with one tap; each pulls the binaries it lists. */
enum class EnvPackage(val tools: List<EnvTool>) {
    NODE(listOf(EnvTool.NODE, EnvTool.NPM, EnvTool.NPX)),
    PYTHON(listOf(EnvTool.PYTHON, EnvTool.PIP, EnvTool.UV, EnvTool.UVX)),
    GIT(listOf(EnvTool.GIT)),
}

data class EnvUiState(
    val checking: Boolean = true,
    /** Tool → version line, `null` when missing. */
    val versions: Map<EnvTool, String?> = emptyMap(),
    val packageManager: String? = null,
    val root: Boolean = true,
    val installing: Boolean = false,
    val log: String = "",
    val exitCode: Int? = null,
    val error: String? = null,
    val directory: String? = null,
)

/**
 * Installs node/npm/npx, python3/pip/uv and git into the server's environment (the proot Ubuntu
 * in Local mode), so `npx`/`uvx` MCP servers start. Node comes from nodejs.org because Ubuntu
 * 22.04's apt package is v12, too old for current MCP servers.
 */
class EnvViewModel(
    private val exec: RemoteExec,
    private val connection: ServerConnectionManager,
    private val installer: EnvInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(EnvUiState())
    val state: StateFlow<EnvUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (!connection.isConnected) connection.connect()
            check()
        }
        viewModelScope.launch {
            var wasInstalling = installer.state.value.installing
            installer.state.collect { run ->
                _state.update { it.copy(installing = run.installing, log = run.log, exitCode = run.exitCode, error = if (run.installing) null else run.error ?: it.error) }
                if (wasInstalling && !run.installing) check()
                wasInstalling = run.installing
            }
        }
    }

    fun check() {
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val result = exec.run(EnvScripts.CHECK, null, CHECK_TIMEOUT_MS)
                val parsed = EnvScripts.parseCheck(result.output)
                _state.update { it.copy(checking = false, versions = parsed.versions, packageManager = parsed.packageManager, root = parsed.root) }
            } catch (e: ApiException) {
                _state.update { it.copy(checking = false, error = e.message) }
            } catch (e: java.io.IOException) {
                _state.update { it.copy(checking = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun install(packages: Set<EnvPackage>) = installer.install(packages)

    companion object {
        private const val CHECK_TIMEOUT_MS = 30_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { EnvViewModel(container.remoteExec, container.connectionManager, container.envInstaller) }
        }
    }
}

/**
 * Runs the environment install outside any screen: leaving the screen used to cancel the job,
 * which removes the PTY and kills apt/dpkg half-way, leaving the package database broken.
 */
class EnvInstaller(private val exec: RemoteExec, private val scope: CoroutineScope) {

    data class Run(val installing: Boolean = false, val log: String = "", val exitCode: Int? = null, val error: String? = null)

    private val _state = MutableStateFlow(Run())
    val state: StateFlow<Run> = _state.asStateFlow()

    fun install(packages: Set<EnvPackage>) {
        if (packages.isEmpty() || _state.value.installing) return
        _state.value = Run(installing = true)
        scope.launch {
            try {
                val result = exec.run(EnvScripts.install(packages), null, INSTALL_TIMEOUT_MS) { chunk ->
                    _state.update { it.copy(log = (it.log + chunk).takeLast(MAX_LOG)) }
                }
                _state.update {
                    it.copy(
                        installing = false,
                        exitCode = result.exitCode,
                        log = result.output.takeLast(MAX_LOG),
                        error = if (result.timedOut) "timeout" else null,
                    )
                }
            } catch (e: ApiException) {
                _state.update { it.copy(installing = false, error = e.message) }
            } catch (e: java.io.IOException) {
                _state.update { it.copy(installing = false, error = e.message ?: e.javaClass.simpleName) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(installing = false) }
                throw e
            }
        }
    }

    private companion object {
        const val INSTALL_TIMEOUT_MS = 30 * 60_000L
        const val MAX_LOG = 40_000
    }
}

/** Shell scripts of the environment screen; pure strings, parsed output is unit-tested. */
object EnvScripts {

    const val NODE_VERSION = "v22.11.0"

    val CHECK: String = buildString {
        append("for t in ").append(EnvTool.entries.joinToString(" ") { it.binary }).append("; do\n")
        append("  if command -v \"\$t\" >/dev/null 2>&1; then echo \"\$t=\$(\"\$t\" --version 2>&1 | head -n 1)\"; else echo \"\$t=\"; fi\n")
        append("done\n")
        append("for pm in apt-get apk dnf pacman; do command -v \$pm >/dev/null 2>&1 && { echo \"pm=\$pm\"; break; }; done\n")
        append("echo \"uid=\$(id -u)\"")
    }

    data class Check(val versions: Map<EnvTool, String?>, val packageManager: String?, val root: Boolean)

    fun parseCheck(output: String): Check {
        val values = output.lines().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }.toMap()
        val versions = EnvTool.entries.associateWith { tool -> values[tool.binary]?.takeIf { it.isNotBlank() } }
        return Check(versions, values["pm"]?.takeIf { it.isNotBlank() }, values["uid"]?.let { it == "0" } ?: true)
    }

    /** Printed when link(2) fails inside proot: the running server predates --link2symlink. */
    const val NO_HARD_LINKS = "Hard links do not work in this environment: restart the server (Settings, Connection, Restart) and run the install again."

    fun install(packages: Set<EnvPackage>): String {
        val node = EnvPackage.NODE in packages
        val python = EnvPackage.PYTHON in packages
        val git = EnvPackage.GIT in packages
        val apt = listOf("ca-certificates", "curl", "xz-utils") +
            (if (python) listOf("python3", "python3-pip", "python3-venv") else emptyList()) +
            (if (git) listOf("git", "openssh-client") else emptyList())
        val apk = listOf("ca-certificates", "curl", "xz") +
            (if (python) listOf("python3", "py3-pip") else emptyList()) +
            (if (git) listOf("git", "openssh-client") else emptyList()) +
            (if (node) listOf("nodejs", "npm") else emptyList())
        val dnf = listOf("ca-certificates", "curl", "xz", "tar") +
            (if (python) listOf("python3", "python3-pip") else emptyList()) +
            (if (git) listOf("git") else emptyList())
        val pacman = listOf("ca-certificates", "curl", "xz") +
            (if (python) listOf("python", "python-pip") else emptyList()) +
            (if (git) listOf("git") else emptyList())
        return buildString {
            appendLine("set -e")
            // A server started before 1.7.1 passes ANDROID_DATA/ANDROID_ROOT into the guest, and pip
            // then dies in platformdirs ("Cannot find path to android app folder").
            appendLine(GuestEnvironment.SHELL_UNSET)
            appendLine("SUDO=; [ \"\$(id -u)\" = 0 ] || SUDO='sudo -n'")

            // A server started before 1.7 runs proot without --link2symlink: dpkg then fails on the
            // first package that replaces a file. Say so instead of leaving apt's code 100.
            appendLine("probe=\$(mktemp 2>/dev/null || echo /tmp/.oc-link-probe); : > \"\$probe\"")
            appendLine("if ! ln \"\$probe\" \"\$probe.l\" 2>/dev/null; then rm -f \"\$probe\"; echo '$NO_HARD_LINKS'; exit 1; fi")
            appendLine("rm -f \"\$probe\" \"\$probe.l\"")
            appendLine("if command -v apt-get >/dev/null 2>&1; then")
            // Finish whatever a previous failed run left half-installed, or apt refuses to continue.
            appendLine("  \$SUDO dpkg --configure -a >/dev/null 2>&1 || true")
            appendLine("  echo '>>> apt-get update'; \$SUDO apt-get update")
            appendLine("  \$SUDO apt-get install -f -y >/dev/null 2>&1 || true")
            appendLine("  echo '>>> apt-get install ${apt.joinToString(" ")}'")
            appendLine("  \$SUDO apt-get install -y --no-install-recommends ${apt.joinToString(" ")}")
            appendLine("elif command -v apk >/dev/null 2>&1; then")
            appendLine("  \$SUDO apk add --no-cache ${apk.joinToString(" ")}")
            appendLine("elif command -v dnf >/dev/null 2>&1; then")
            appendLine("  \$SUDO dnf install -y ${dnf.joinToString(" ")}")
            appendLine("elif command -v pacman >/dev/null 2>&1; then")
            appendLine("  \$SUDO pacman -Sy --noconfirm --needed ${pacman.joinToString(" ")}")
            appendLine("else echo 'No supported package manager (apt-get, apk, dnf, pacman)'; exit 1; fi")
            if (node) {
                appendLine("need_node=1")
                appendLine("if command -v node >/dev/null 2>&1; then")
                appendLine("  major=\$(node -p 'process.versions.node.split(\".\")[0]' 2>/dev/null || echo 0)")
                appendLine("  [ \"\$major\" -ge 18 ] 2>/dev/null && need_node=0")
                appendLine("fi")
                appendLine("if [ \$need_node = 1 ] && ! command -v apk >/dev/null 2>&1; then")
                appendLine("  case \"\$(uname -m)\" in aarch64|arm64) arch=arm64;; x86_64|amd64) arch=x64;; armv7l) arch=armv7l;; *) echo \"Unsupported CPU: \$(uname -m)\"; exit 1;; esac")
                appendLine("  url=https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-\$arch.tar.xz")
                appendLine("  echo \">>> \$url\"")
                appendLine("  curl -fSL --retry 3 -o /tmp/node.tar.xz \"\$url\"")
                appendLine("  \$SUDO tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1 --no-same-owner --exclude=CHANGELOG.md --exclude=README.md --exclude=LICENSE")
                appendLine("  rm -f /tmp/node.tar.xz")
                appendLine("fi")
                appendLine("echo \">>> node \$(node --version), npm \$(npm --version)\"")
            }
            if (python) {
                appendLine("echo '>>> pip install uv'")
                appendLine("export PIP_ROOT_USER_ACTION=ignore PIP_DISABLE_PIP_VERSION_CHECK=1")
                appendLine("python3 -m pip install --upgrade uv 2>&1 || python3 -m pip install --upgrade --break-system-packages uv")
                appendLine("echo \">>> \$(python3 --version), \$(uv --version 2>/dev/null || echo 'uv missing from PATH')\"")
            }
            if (git) appendLine("echo \">>> \$(git --version)\"")
            append("echo '>>> done'")
        }
    }
}
