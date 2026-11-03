package ai.opencode.android.data.repo

import ai.opencode.android.data.remote.ApiException
import ai.opencode.android.data.remote.NetworkErrors
import ai.opencode.android.data.remote.UnauthorizedException
import kotlinx.coroutines.CancellationException

/** Result of one repository call, carrying a UI-ready message instead of a raw exception. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(
        val message: String,
        val unauthorized: Boolean = false,
        val notConnected: Boolean = false,
    ) : ApiResult<Nothing>
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Ok)?.value

inline fun <T> ApiResult<T>.onOk(block: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Ok) block(value)
    return this
}

suspend fun <T> runApiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (unauthorized: UnauthorizedException) {
    ApiResult.Err(NetworkErrors.describe(unauthorized), unauthorized = true)
} catch (api: ApiException) {
    ApiResult.Err(NetworkErrors.describe(api), notConnected = neverReachedServer(api))
} catch (t: Throwable) {
    ApiResult.Err(NetworkErrors.describe(t), notConnected = neverReachedServer(t))
}

/**
 * True when the request certainly did not reach the server (refused, no route, DNS), so sending it
 * again cannot duplicate anything. Timeouts after the connection was made do not count.
 */
fun neverReachedServer(t: Throwable): Boolean {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < 8) {
        when (current) {
            is java.net.ConnectException, is java.net.UnknownHostException, is java.net.NoRouteToHostException,
            is java.net.PortUnreachableException,
            -> return true
            is java.net.SocketTimeoutException -> if (current.message?.contains("connect", ignoreCase = true) == true) return true
        }
        current = current.cause
        depth++
    }
    return false
}
