package ai.opencode.android.server

import java.net.HttpURLConnection
import java.net.URL

/** Minimal seam over an HTTP GET so the probe can be unit tested without sockets. */
interface HttpPinger {
    /** Returns the HTTP status code, or null when the endpoint could not be reached. */
    fun status(path: String, timeoutMs: Int): Int?
}

/** Real implementation: plain HttpURLConnection against 127.0.0.1 (no proxy, no redirects). */
class LocalHttpPinger(private val port: Int) : HttpPinger {
    override fun status(path: String, timeoutMs: Int): Int? = runCatching {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
            instanceFollowRedirects = false
            useCaches = false
        }
        try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

data class HealthResult(
    val healthy: Boolean,
    val path: String? = null,
    val code: Int = 0,
    val reason: String? = null,
) {
    companion object {
        fun up(path: String, code: Int) = HealthResult(healthy = true, path = path, code = code)

        fun down(reason: String) = HealthResult(healthy = false, reason = reason)
    }
}

/**
 * Server health check.
 *
 * `GET /` is the primary probe (the opencode server answers it as soon as it is listening), with
 * `/api/info` and `/config` as fallbacks for servers that only expose API routes. Any HTTP answer
 * below 500 means "the process is alive and serving"; 401 is healthy too because it only means the
 * request lacked credentials.
 */
class HealthProbe(
    private val pinger: HttpPinger,
    private val paths: List<String> = DEFAULT_PATHS,
    private val timeoutMs: Int = 2_500,
) {

    fun check(): HealthResult {
        var lastCode = 0
        for (path in paths) {
            val code = pinger.status(path, timeoutMs) ?: continue
            lastCode = code
            if (code in 200..499) return HealthResult.up(path, code)
        }
        return HealthResult.down(
            if (lastCode == 0) "No answer on 127.0.0.1" else "Server answered HTTP $lastCode",
        )
    }

    companion object {
        val DEFAULT_PATHS = listOf("/", "/api/info", "/config")
    }
}
