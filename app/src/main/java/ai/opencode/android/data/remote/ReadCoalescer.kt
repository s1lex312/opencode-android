package ai.opencode.android.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Shares identical GETs between callers. Every SSE frame fans out to several listeners (session
 * list, chat, notifier, busy probes) that each re-read the same resource within milliseconds; the
 * server's answer is identical, so one request serves them all.
 *
 * Correctness rests on an epoch: [invalidate] bumps it whenever the server may have changed (an
 * event arrived, a write was sent or answered). A caller only joins a read started in the current
 * epoch, so it never gets an answer older than the change that made it ask.
 */
internal class ReadCoalescer(
    private val reuseMillis: Long = REUSE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Flight(val epoch: Long, val result: Deferred<JsonElement?>) {
        @Volatile var finishedAt: Long = Long.MAX_VALUE
    }

    private val epoch = AtomicLong()
    private val flights = ConcurrentHashMap<String, Flight>()
    private val sharedCount = AtomicLong()

    val shared: Long get() = sharedCount.get()

    fun invalidate() {
        epoch.incrementAndGet()
        if (flights.size > MAX_ENTRIES) flights.clear()
    }

    /**
     * [load] runs detached from the caller, so one caller leaving (a closed screen) does not
     * cancel the answer the others wait for. [reusable] lets a finished answer serve callers for
     * [reuseMillis] while nothing changed; otherwise only an in-flight request is shared.
     */
    suspend fun get(key: String, reusable: Boolean, load: () -> JsonElement?): JsonElement? {
        while (true) {
            val current = epoch.get()
            val existing = flights[key]
            if (existing != null && existing.epoch == current && usable(existing, reusable)) {
                sharedCount.incrementAndGet()
                return existing.result.await()
            }
            val result = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { load() }
            val mine = Flight(current, result)
            val claimed = if (existing == null) flights.putIfAbsent(key, mine) == null else flights.replace(key, existing, mine)
            if (!claimed) {
                result.cancel()
                continue
            }
            result.invokeOnCompletion { error ->
                mine.finishedAt = clock()
                if (error != null) flights.remove(key, mine)
            }
            result.start()
            return result.await()
        }
    }

    private fun usable(flight: Flight, reusable: Boolean): Boolean {
        if (flight.result.isCancelled) return false
        if (!flight.result.isCompleted) return true
        if (!reusable) return false
        return clock() - flight.finishedAt <= reuseMillis
    }

    companion object {
        const val REUSE_MILLIS = 1_500L
        private const val MAX_ENTRIES = 256
    }
}
