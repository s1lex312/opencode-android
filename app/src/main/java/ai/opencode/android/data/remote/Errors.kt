package ai.opencode.android.data.remote

import java.io.IOException

/** A non-2xx HTTP answer from the opencode server. [body] is already truncated and redacted. */
open class ApiException(
    val statusCode: Int?,
    override val message: String,
    val body: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

class UnauthorizedException(message: String = "Authentication required") : ApiException(401, message)

class NotFoundException(message: String = "Not found") : ApiException(404, message)

class SseAuthException(message: String) : ApiException(401, message)

/** Maps low-level failures to a message that is safe and useful in the UI. */
object NetworkErrors {

    fun describe(error: Throwable): String = when (error) {
        is UnauthorizedException -> "Authentication failed. Check the password or token in Settings."
        is ApiException -> error.message
        is java.net.UnknownHostException -> "Host not found. Check the server address or your network."
        is java.net.ConnectException -> "Connection refused. Is the opencode server running and reachable?"
        is java.net.SocketTimeoutException -> "The server did not answer in time."
        is javax.net.ssl.SSLException -> "TLS handshake failed. Check the server certificate."
        is IOException -> error.message ?: "Network error"
        else -> error.message ?: error.javaClass.simpleName
    }
}
