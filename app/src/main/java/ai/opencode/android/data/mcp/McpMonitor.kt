package ai.opencode.android.data.mcp

import ai.opencode.android.data.model.McpServerInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the app has seen of each MCP server since it started: status changes, how long connects
 * took, and the last error even after the server recovered. opencode only reports the current
 * status, so this is the history the MCP screen shows.
 */
class McpMonitor(private val clock: () -> Long = System::currentTimeMillis) {

    data class Change(val at: Long, val status: McpServerInfo.Status, val error: String?)

    data class Connect(val at: Long, val millis: Long, val ok: Boolean, val error: String?)

    data class Health(
        val changes: List<Change> = emptyList(),
        val connects: List<Connect> = emptyList(),
        val lastError: String? = null,
        val lastErrorAt: Long? = null,
    ) {
        val status: McpServerInfo.Status? get() = changes.lastOrNull()?.status

        /** Since when the server has had its current status. */
        val since: Long? get() = changes.lastOrNull()?.at
        val lastConnect: Connect? get() = connects.lastOrNull()
    }

    private val _health = MutableStateFlow<Map<String, Health>>(emptyMap())
    val health: StateFlow<Map<String, Health>> = _health.asStateFlow()

    /** Records a `GET /mcp` answer; only changes of status or error are kept. */
    fun observe(servers: List<McpServerInfo>) {
        val now = clock()
        _health.update { all ->
            val next = all.toMutableMap()
            servers.forEach { server ->
                val current = next[server.name] ?: Health()
                val last = current.changes.lastOrNull()
                val error = server.error?.takeIf { it.isNotBlank() }
                if (last != null && last.status == server.status && last.error == error) return@forEach
                next[server.name] = current.copy(
                    changes = (current.changes + Change(now, server.status, error)).takeLast(MAX_CHANGES),
                    lastError = error ?: current.lastError,
                    lastErrorAt = if (error != null) now else current.lastErrorAt,
                )
            }
            next
        }
    }

    /** Runs a connect (or a save that connects) of [name] and records how long it took. */
    suspend fun <T> timed(name: String, block: suspend () -> T, failure: (T) -> String?): T {
        val started = clock()
        val result = block()
        val error = failure(result)
        val connect = Connect(started, clock() - started, error == null, error)
        _health.update { all ->
            val current = all[name] ?: Health()
            all + (
                name to current.copy(
                    connects = (current.connects + connect).takeLast(MAX_CONNECTS),
                    lastError = error ?: current.lastError,
                    lastErrorAt = if (error != null) connect.at else current.lastErrorAt,
                )
                )
        }
        return result
    }

    fun forget(name: String) = _health.update { it - name }

    companion object {
        private const val MAX_CHANGES = 30
        private const val MAX_CONNECTS = 10
    }
}
