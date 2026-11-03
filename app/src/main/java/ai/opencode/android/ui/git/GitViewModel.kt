package ai.opencode.android.ui.git

import ai.opencode.android.data.diff.DiffHunk
import ai.opencode.android.data.diff.UnifiedDiffParser
import ai.opencode.android.data.git.Git
import ai.opencode.android.data.git.GitCommit
import ai.opencode.android.data.git.GitFile
import ai.opencode.android.data.git.GitStatus
import ai.opencode.android.data.git.RawFileDiff
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.remote.ApiException
import ai.opencode.android.data.remote.RemoteExec
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.di.AppContainer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Diff of one file on one side (index or worktree), loaded when the row is expanded. */
data class FileDiffState(
    val loading: Boolean = true,
    val raw: RawFileDiff? = null,
    /** Parsed hunks in the same order as `raw.hunks`, for rendering. */
    val hunks: List<DiffHunk> = emptyList(),
    val error: String? = null,
)

/** Output of the last git command, shown under the toolbar. */
data class GitOutput(val title: String, val text: String, val ok: Boolean)

data class GitUiState(
    val directory: String? = null,
    val loading: Boolean = true,
    val status: GitStatus? = null,
    val log: List<GitCommit> = emptyList(),
    val diffs: Map<String, FileDiffState> = emptyMap(),
    /** Label of the command in flight; disables the buttons. */
    val running: String? = null,
    val output: GitOutput? = null,
    val error: String? = null,
    val message: String = "",
    val amend: Boolean = false,
)

/**
 * Git review on the opencode server: status, per-hunk staging, commit, push/pull. Every action is
 * a `git` invocation through [RemoteExec] in the chat's directory, so it works the same for the
 * in-app server and for a laptop.
 */
class GitViewModel(
    private val exec: RemoteExec,
    private val connection: ServerConnectionManager,
    cwd: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(GitUiState(directory = cwd))
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (!connection.isConnected) connection.connect()
            val connected = withTimeoutOrNull(CONNECT_WAIT_MS) {
                connection.state.first { it is ConnectionState.Connected }
            } as? ConnectionState.Connected
            if (_state.value.directory == null) _state.update { it.copy(directory = connected?.info?.directory) }
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val status = exec(Git.STATUS_SCRIPT)
            if (status == null) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val parsed = Git.parseStatus(status.output)
            if (!status.ok && parsed.isRepo) {
                _state.update { it.copy(loading = false, error = status.output.ifBlank { "git status failed" }) }
                return@launch
            }
            val log = if (parsed.isRepo) exec(Git.LOG_SCRIPT)?.output?.let(Git::parseLog).orEmpty() else emptyList()
            // Keep open rows open, with fresh content.
            val open = _state.value.diffs.keys
            _state.update { it.copy(loading = false, status = parsed, log = log, diffs = emptyMap()) }
            open.forEach { key ->
                val staged = key.startsWith(STAGED)
                val path = key.substring(2)
                parsed.files.firstOrNull { it.path == path && (if (staged) it.staged else it.unstaged) }?.let { loadDiff(it, staged) }
            }
        }
    }

    fun toggle(file: GitFile, staged: Boolean) {
        val key = key(file, staged)
        if (_state.value.diffs.containsKey(key)) {
            _state.update { it.copy(diffs = it.diffs - key) }
        } else {
            loadDiff(file, staged)
        }
    }

    private fun loadDiff(file: GitFile, staged: Boolean) {
        val key = key(file, staged)
        _state.update { it.copy(diffs = it.diffs + (key to FileDiffState())) }
        viewModelScope.launch {
            val result = exec(Git.diffScript(file, staged), reportErrors = false)
            val raw = result?.output?.let(Git::splitDiff)?.firstOrNull()
            val hunks = raw?.let { UnifiedDiffParser.parse(it.text).firstOrNull()?.hunks }.orEmpty()
            val diff = FileDiffState(
                loading = false,
                raw = raw,
                hunks = hunks,
                error = if (raw == null) result?.output?.takeIf { it.isNotBlank() } else null,
            )
            _state.update { if (it.diffs.containsKey(key)) it.copy(diffs = it.diffs + (key to diff)) else it }
        }
    }

    fun stage(file: GitFile) = action("git add", Git.stageScript(listOf(file.path)))

    fun unstage(file: GitFile) = action("git restore --staged", Git.unstageScript(listOf(file.path)))

    fun discard(file: GitFile) = action("git checkout", Git.discardScript(file))

    fun stageAll() = action("git add -A", Git.STAGE_ALL_SCRIPT)

    fun unstageAll() {
        val staged = _state.value.status?.staged?.map { it.path }.orEmpty()
        if (staged.isNotEmpty()) action("git restore --staged", Git.unstageScript(staged))
    }

    /** [staged] picks the side: unstage from the index, or stage / discard ([discard]) from the worktree. */
    fun hunk(file: GitFile, staged: Boolean, index: Int, discard: Boolean = false) {
        val raw = _state.value.diffs[key(file, staged)]?.raw ?: return
        val hunk = raw.hunks.getOrNull(index) ?: return
        val patch = raw.patchFor(hunk)
        when {
            staged -> action("git apply --cached -R", Git.applyScript(patch, cached = true, reverse = true))
            discard -> action("git apply -R", Git.applyScript(patch, cached = false, reverse = true))
            else -> action("git apply --cached", Git.applyScript(patch, cached = true, reverse = false))
        }
    }

    fun setMessage(message: String) = _state.update { it.copy(message = message) }

    fun setAmend(amend: Boolean) = _state.update { it.copy(amend = amend) }

    fun commit(push: Boolean) {
        val current = _state.value
        val message = current.message.trim()
        if (message.isEmpty() && !current.amend) return
        val stageAll = current.status?.staged.isNullOrEmpty()
        val commit = if (message.isEmpty()) {
            (if (stageAll) "git add -A && " else "") + "git commit --amend --no-edit"
        } else {
            Git.commitScript(message, current.amend, stageAll)
        }
        val script = commit + if (push) " && { " + Git.PUSH_SCRIPT + "; }" else ""
        action(if (push) "git commit && git push" else "git commit", script) { ok ->
            if (ok) _state.update { it.copy(message = "", amend = false) }
        }
    }

    fun push() = action("git push", Git.PUSH_SCRIPT, timeoutMs = NETWORK_TIMEOUT_MS)

    fun pull() = action("git pull --ff-only", Git.PULL_SCRIPT, timeoutMs = NETWORK_TIMEOUT_MS)

    fun fetch() = action("git fetch", Git.FETCH_SCRIPT, timeoutMs = NETWORK_TIMEOUT_MS)

    fun init() = action("git init", Git.INIT_SCRIPT)

    fun dismissOutput() = _state.update { it.copy(output = null) }

    private fun action(title: String, script: String, timeoutMs: Long = RemoteExec.DEFAULT_TIMEOUT_MS, then: (Boolean) -> Unit = {}) {
        if (_state.value.running != null) return
        _state.update { it.copy(running = title) }
        viewModelScope.launch {
            val result = exec(script, timeoutMs = timeoutMs)
            val ok = result?.ok == true
            _state.update {
                it.copy(
                    running = null,
                    output = result?.let { r -> GitOutput(title, r.output.ifBlank { if (r.ok) "OK" else "exit ${r.exitCode}" }, ok) },
                )
            }
            then(ok)
            refresh()
        }
    }

    /** `null` (with [GitUiState.error] set) when the command could not run at all. */
    private suspend fun exec(script: String, timeoutMs: Long = RemoteExec.DEFAULT_TIMEOUT_MS, reportErrors: Boolean = true): RemoteExec.Result? =
        try {
            exec.run(script, _state.value.directory, timeoutMs)
        } catch (e: ApiException) {
            if (reportErrors) _state.update { it.copy(error = e.message) }
            null
        } catch (e: java.io.IOException) {
            if (reportErrors) _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            null
        }

    companion object {
        private const val STAGED = "S:"
        private const val WORKTREE = "W:"
        private const val CONNECT_WAIT_MS = 15_000L
        private const val NETWORK_TIMEOUT_MS = 300_000L

        fun key(file: GitFile, staged: Boolean): String = (if (staged) STAGED else WORKTREE) + file.path

        fun factory(container: AppContainer, cwd: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer { GitViewModel(container.remoteExec, container.connectionManager, cwd) }
        }
    }
}
