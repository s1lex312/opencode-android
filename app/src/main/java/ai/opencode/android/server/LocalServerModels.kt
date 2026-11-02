package ai.opencode.android.server

import ai.opencode.android.settings.LocalServerSettings

/** Lifecycle of the in-app proot server. */
data class LocalServerState(
    val phase: Phase = Phase.STOPPED,
    val message: String? = null,
    val logs: List<String> = emptyList(),
    val pid: Int? = null,
    val healthy: Boolean = false,
    val port: Int = 4096,
    val restarts: Int = 0,
    val envVersion: String? = null,
    val logFilePath: String? = null,
) {
    enum class Phase { STOPPED, STARTING, READY, FAILED, STOPPING }

    val running: Boolean get() = phase == Phase.STARTING || phase == Phase.READY
}

/** Result of a "make sure the local server is up" request. */
sealed interface LocalReadyResult {
    data object Ready : LocalReadyResult

    data class NotInstalled(val message: String) : LocalReadyResult

    data class Failed(val message: String) : LocalReadyResult

    data object Timeout : LocalReadyResult
}

/** Convenience view used by the settings screen. */
fun LocalServerSettings.describePort(): String = "127.0.0.1:$port"
