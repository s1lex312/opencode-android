package ai.opencode.android.server

/** Lifecycle of the optional, user-provisioned proot-based server. */
data class LocalServerState(
    val phase: Phase = Phase.STOPPED,
    val message: String? = null,
    val logs: List<String> = emptyList(),
    val pid: Int? = null,
    val healthy: Boolean = false,
) {
    enum class Phase { STOPPED, STARTING, READY, FAILED, STOPPING }

    val running: Boolean get() = phase == Phase.STARTING || phase == Phase.READY
}
