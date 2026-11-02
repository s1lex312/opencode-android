package ai.opencode.android.server

/**
 * Restart/backoff policy for the local server process.
 *
 * Restarts are capped so a broken rootfs or an occupied port cannot spin the CPU forever: the
 * supervisor gives up after [maxRestarts] attempts and leaves the UI in FAILED with the log.
 */
data class RestartPolicy(
    val maxRestarts: Int = 5,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
) {

    fun shouldRestart(completedRestarts: Int): Boolean = completedRestarts < maxRestarts

    /** Exponential backoff: 1s, 2s, 4s, 8s, 16s, then capped at [maxDelayMs]. */
    fun delayFor(attempt: Int): Long {
        val safeAttempt = attempt.coerceIn(1, 10)
        val shift = (safeAttempt - 1).coerceIn(0, 5)
        return (baseDelayMs shl shift).coerceAtMost(maxDelayMs)
    }

    companion object {
        val DEFAULT = RestartPolicy()
    }
}
