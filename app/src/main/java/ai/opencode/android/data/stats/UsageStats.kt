package ai.opencode.android.data.stats

import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.settings.BudgetSettings
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Spend of one model on one day, from the assistant messages of one session. */
@Serializable
data class CostEntry(
    val day: String,
    val model: String,
    val cost: Double = 0.0,
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val turns: Int = 0,
) {
    val tokens: Long get() = input + output + reasoning + cacheRead + cacheWrite
}

@Serializable
data class SessionCost(val updated: Long, val entries: List<CostEntry>)

@Serializable
private data class StatsFile(val servers: Map<String, Map<String, SessionCost>> = emptyMap())

data class ModelCost(val model: String, val cost: Double, val tokens: Long, val turns: Int)

data class StatsSummary(
    val today: Double = 0.0,
    val week: Double = 0.0,
    val month: Double = 0.0,
    val total: Double = 0.0,
    val turns: Int = 0,
    val tokens: Long = 0,
    /** Oldest first, one entry per day of the window, zero-filled. */
    val byDay: List<Pair<LocalDate, Double>> = emptyList(),
    val byModel: List<ModelCost> = emptyList(),
)

data class BudgetStatus(
    val daily: Double,
    val dailyLimit: Double,
    val monthly: Double,
    val monthlyLimit: Double,
    val warnPercent: Int,
) {
    private fun ratio(spent: Double, limit: Double) = if (limit > 0) spent / limit else 0.0

    val dailyRatio: Double get() = ratio(daily, dailyLimit)
    val monthlyRatio: Double get() = ratio(monthly, monthlyLimit)
    val exceeded: Boolean get() = (dailyLimit > 0 && daily >= dailyLimit) || (monthlyLimit > 0 && monthly >= monthlyLimit)
    val warning: Boolean get() = !exceeded && maxOf(dailyRatio, monthlyRatio) * 100 >= warnPercent
    val hasLimit: Boolean get() = dailyLimit > 0 || monthlyLimit > 0
}

data class StatsState(
    val loading: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val summary: StatsSummary = StatsSummary(),
    val refreshedAt: Long = 0L,
)

/**
 * Spending per day and model, built from the cost opencode records on every assistant message.
 * Per-session results are cached by the session's `updated` time, so a refresh only refetches
 * chats that changed. Chats deleted on the server keep counting: money spent stays spent.
 */
class UsageStats(
    private val file: File,
    private val repository: SessionRepository,
    private val connection: ServerConnectionManager,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    constructor(context: Context, repository: SessionRepository, connection: ServerConnectionManager) :
        this(File(context.applicationContext.filesDir, "usage-stats.json"), repository, connection)

    private val mutex = Mutex()
    private var cache: StatsFile = load()
    private val _state = MutableStateFlow(StatsState(summary = summarizeCurrent()))
    val state: StateFlow<StatsState> = _state.asStateFlow()

    suspend fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null, done = 0, total = 0) }
        val sessions = when (val result = repository.loadSessions()) {
            is ApiResult.Ok -> result.value
            is ApiResult.Err -> {
                _state.update { it.copy(loading = false, error = result.message) }
                return
            }
        }
        val known = mutex.withLock { cache.servers[connection.serverKey].orEmpty() }
        val stale = sessions.filter { session -> known[session.id]?.updated != session.updated || session.busy }
        _state.update { it.copy(total = stale.size) }
        val gate = Semaphore(PARALLEL)
        val fresh = coroutineScope {
            stale.map { session ->
                async {
                    gate.withPermit {
                        val messages = (repository.messages(session.id) as? ApiResult.Ok)?.value
                        _state.update { it.copy(done = it.done + 1) }
                        messages?.let { session.id to SessionCost(session.updated, aggregate(it, zone())) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        store(fresh)
        _state.update { it.copy(loading = false, summary = summarizeCurrent(), refreshedAt = clock()) }
    }

    /** Cheap update after a turn: only this chat's messages are re-aggregated. */
    suspend fun record(sessionId: String, messages: List<ChatMessage>) {
        if (messages.none { it.role == Role.ASSISTANT && (it.cost ?: 0.0) > 0 }) return
        store(listOf(sessionId to SessionCost(clock(), aggregate(messages, zone()))))
        _state.update { it.copy(summary = summarizeCurrent()) }
    }

    fun budgetStatus(budget: BudgetSettings): BudgetStatus {
        val summary = _state.value.summary
        return BudgetStatus(summary.today, budget.daily, summary.month, budget.monthly, budget.warnPercent)
    }

    private suspend fun store(entries: List<Pair<String, SessionCost>>) {
        if (entries.isEmpty()) return
        mutex.withLock {
            val key = connection.serverKey
            val server = cache.servers[key].orEmpty() + entries
            cache = cache.copy(servers = cache.servers + (key to server))
            val snapshot = cache
            withContext(Dispatchers.IO) { runCatching { file.writeText(JSON.encodeToString(StatsFile.serializer(), snapshot)) } }
        }
    }

    private fun summarizeCurrent(): StatsSummary {
        val entries = cache.servers.values.flatMap { server -> server.values.flatMap { it.entries } }
        return summarize(entries, Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate())
    }

    private fun load(): StatsFile = runCatching {
        if (file.exists()) JSON.decodeFromString(StatsFile.serializer(), file.readText()) else StatsFile()
    }.getOrDefault(StatsFile())

    companion object {
        private const val PARALLEL = 4
        const val DAYS = 30

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /** Groups assistant turns by local day and `provider/model`. */
        fun aggregate(messages: List<ChatMessage>, zone: ZoneId): List<CostEntry> =
            messages
                .filter { it.role == Role.ASSISTANT && (it.cost != null || it.tokens != null) && it.created > 0 }
                .groupBy { message ->
                    val day = Instant.ofEpochMilli(message.created).atZone(zone).toLocalDate().toString()
                    val model = listOfNotNull(message.providerId, message.model).joinToString("/").ifBlank { "unknown" }
                    day to model
                }
                .map { (key, turns) ->
                    CostEntry(
                        day = key.first,
                        model = key.second,
                        cost = turns.sumOf { it.cost ?: 0.0 },
                        input = turns.sumOf { it.tokens?.input ?: 0L },
                        output = turns.sumOf { it.tokens?.output ?: 0L },
                        reasoning = turns.sumOf { it.tokens?.reasoning ?: 0L },
                        cacheRead = turns.sumOf { it.tokens?.cacheRead ?: 0L },
                        cacheWrite = turns.sumOf { it.tokens?.cacheWrite ?: 0L },
                        turns = turns.size,
                    )
                }

        fun summarize(entries: List<CostEntry>, today: LocalDate, days: Int = DAYS): StatsSummary {
            val parsed = entries.mapNotNull { entry -> runCatching { LocalDate.parse(entry.day) }.getOrNull()?.let { it to entry } }
            fun spent(predicate: (LocalDate) -> Boolean) = parsed.filter { predicate(it.first) }.sumOf { it.second.cost }
            val windowStart = today.minusDays((days - 1).toLong())
            val perDay = parsed.filter { !it.first.isBefore(windowStart) && !it.first.isAfter(today) }
                .groupBy({ it.first }, { it.second.cost })
                .mapValues { it.value.sum() }
            return StatsSummary(
                today = spent { it == today },
                week = spent { !it.isBefore(today.minusDays(6)) && !it.isAfter(today) },
                month = spent { it.year == today.year && it.month == today.month },
                total = parsed.sumOf { it.second.cost },
                turns = parsed.sumOf { it.second.turns },
                tokens = parsed.sumOf { it.second.tokens },
                byDay = (0 until days).map { offset -> windowStart.plusDays(offset.toLong()).let { day -> day to (perDay[day] ?: 0.0) } },
                byModel = parsed.map { it.second }.groupBy { it.model }.map { (model, list) ->
                    ModelCost(model, list.sumOf { it.cost }, list.sumOf { it.tokens }, list.sumOf { it.turns })
                }.sortedByDescending { it.cost },
            )
        }
    }
}
