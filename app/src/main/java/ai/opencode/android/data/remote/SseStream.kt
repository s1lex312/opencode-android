package ai.opencode.android.data.remote

import ai.opencode.android.BuildConfig
import ai.opencode.android.security.Redaction
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.min
import kotlin.random.Random

/** Signals produced by a long-lived SSE subscription. */
sealed interface StreamSignal {
    data object Opened : StreamSignal

    data class Frame(val type: String?, val data: String) : StreamSignal

    data class Failure(val message: String, val authFailure: Boolean = false, val fatal: Boolean = false) : StreamSignal
}

/**
 * Live event stream from the opencode server.
 *
 * Reconnection is handled here (not by okhttp-sse) so the app controls backoff, surfaces
 * connection state to the UI and stops retrying when retrying cannot help (401/404).
 * The blocking socket lives on [Dispatchers.IO]; frames are parsed by [SseParser].
 */
class SseStream(
    private val client: OkHttpClient,
    private val request: Request,
    private val maxBackoffMillis: Long = 30_000,
) {

    fun signals(): Flow<StreamSignal> = flow {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            var openedThisRound = false
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = runCatching { response.body?.string() }.getOrNull()
                        val message = body?.let { Redaction.preview(it) }
                        val failure = when (response.code) {
                            401, 403 -> ApiException(401, "Authentication required by the opencode server")
                            404 -> ApiException(404, "Event stream endpoint not found (HTTP 404)")
                            else -> ApiException(response.code, "Event stream failed: HTTP ${response.code}")
                        }
                        val authFailure = response.code == 401 || response.code == 403
                        val fatal = authFailure || response.code == 404
                        emit(
                            StreamSignal.Failure(
                                message = failure.message + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
                                authFailure = authFailure,
                                fatal = fatal,
                            ),
                        )
                        if (fatal) return@flow
                        throw RetrySignal
                    }

                    val source = response.body?.source()
                        ?: throw IOException("Empty event stream body")
                    emit(StreamSignal.Opened)
                    openedThisRound = true
                    attempt = 0

                    val parser = SseParser()
                    while (currentCoroutineContext().isActive) {
                        val line = source.readUtf8Line() ?: break
                        val event = parser.feed(line) ?: continue
                        emit(StreamSignal.Frame(event.type, event.data))
                    }
                    parser.flush()?.let { emit(StreamSignal.Frame(it.type, it.data)) }
                    emit(StreamSignal.Failure("Event stream closed by the server", fatal = false))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: RetrySignal) {
                // Already reported to the collector above; just fall through to backoff.
            } catch (t: Throwable) {
                if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "SSE round failed: ${t.javaClass.simpleName}: ${Redaction.preview(t.message, 120)}")
                }
                emit(
                    StreamSignal.Failure(
                        message = NetworkErrors.describe(t),
                        authFailure = t is UnauthorizedException,
                        fatal = t is UnauthorizedException || t is NotFoundException,
                    ),
                )
                if (t is UnauthorizedException || t is NotFoundException) return@flow
            }

            if (!currentCoroutineContext().isActive) break
            attempt = if (openedThisRound) 1 else attempt + 1
            delay(backoffMillis(attempt))
        }
    }.flowOn(Dispatchers.IO)

    /** Internal marker: the failure was already emitted, only the backoff is left. */
    private object RetrySignal : IOException("retrying")

    private fun backoffMillis(attempt: Int): Long {
        val exponential = 1_000L shl min(attempt.coerceIn(0, 5), 5)
        val capped = min(exponential, maxBackoffMillis)
        val jitter = Random.nextLong(0, 400)
        return capped + jitter
    }

    private companion object {
        const val LOG_TAG = "opencode-sse"
    }
}
