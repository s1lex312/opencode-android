package ai.opencode.android.data.cache

import ai.opencode.android.data.model.ServerSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tiny on-disk cache for the session list so the app shows something useful when the server is
 * unreachable (and so a cold start does not flash an empty screen).
 *
 * Deliberately not Room: the mobile client is a thin view over remote state, the cached payload is
 * one small JSON document, and a plain file avoids a second source of truth (and an annotation
 * processor) for no benefit. Everything lives in the app-private cache directory.
 */
class SessionCache(context: Context) {

    private val directory = File(context.applicationContext.cacheDir, "opencode-cache")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun save(serverKey: String, sessions: List<ServerSession>) = withContext(Dispatchers.IO) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            val payload = CachedSessions(
                savedAt = System.currentTimeMillis(),
                sessions = sessions.take(200).map { it.toCached() },
            )
            fileFor(serverKey).writeText(json.encodeToString(CachedSessions.serializer(), payload))
        }
        Unit
    }

    suspend fun load(serverKey: String): List<ServerSession> = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileFor(serverKey)
            if (!file.exists()) return@runCatching emptyList()
            val payload = json.decodeFromString(CachedSessions.serializer(), file.readText())
            payload.sessions.map { it.toModel() }
        }.getOrDefault(emptyList())
    }

    private fun fileFor(serverKey: String): File {
        val safe = Integer.toHexString(serverKey.hashCode())
        return File(directory, "sessions-$safe.json")
    }

    @Serializable
    private data class CachedSessions(
        val savedAt: Long,
        val sessions: List<CachedSession>,
    )

    @Serializable
    private data class CachedSession(
        val id: String,
        val title: String,
        val directory: String? = null,
        val created: Long = 0,
        val updated: Long = 0,
        val parentID: String? = null,
    ) {
        fun toModel() = ServerSession(id, title, directory, created, updated, parentID, busy = false)
    }

    private fun ServerSession.toCached() = CachedSession(id, title, directory, created, updated, parentID)
}
