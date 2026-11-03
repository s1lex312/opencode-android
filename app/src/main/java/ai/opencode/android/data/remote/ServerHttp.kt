package ai.opencode.android.data.remote

import ai.opencode.android.BuildConfig
import ai.opencode.android.security.NetworkPolicy
import ai.opencode.android.security.Redaction
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

data class ServerCredentials(
    val authMode: ai.opencode.android.settings.AuthMode,
    val username: String,
    val password: String? = null,
    val token: String? = null,
)

/**
 * Thin HTTP facade over one opencode server instance.
 *
 * Responsibilities:
 *  * attach Basic/Bearer credentials to every request without ever exposing them to callers;
 *  * re-check the cleartext/HTTPS policy on each request;
 *  * map non-2xx responses to typed exceptions with a redacted, truncated body preview;
 *  * keep bodies and headers out of logcat.
 */
class ServerHttp(
    val baseUrl: String,
    private val credentials: ServerCredentials,
    private val allowPrivateNetworkCleartext: Boolean,
    timeoutSeconds: Long = 60,
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .proxySelector(ai.opencode.android.net.AppProxySelector)
        .addInterceptor(NetworkPolicyInterceptor(allowPrivateNetworkCleartext))
        .apply { if (credentials.authMode != ai.opencode.android.settings.AuthMode.NONE) addInterceptor(AuthInterceptor(credentials)) }
        .apply { if (BuildConfig.DEBUG) addInterceptor(MetadataLoggingInterceptor()) }
        .build()

    /** Same connection pool/settings but no read timeout, required for long-lived SSE streams. */
    val sseClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * For endpoints that answer only when the agent's turn is over (`/command`, `/summarize`,
     * `/shell`, MCP OAuth): a slow model or a long task must not be cut off by the read timeout.
     */
    private val longClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val parsed = ("$baseUrl$normalizedPath").toHttpUrlOrNull()
            ?: throw ApiException(null, "Invalid server URL")
        val builder = parsed.newBuilder()
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonElement? =
        withContext(Dispatchers.IO) { execute(Request.Builder().url(url(path, query)).get().build()) }

    suspend fun postJson(
        path: String,
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonElement? = withContext(Dispatchers.IO) {
        val requestBody = (body?.toString() ?: "{}").toRequestBody(jsonMediaType)
        execute(Request.Builder().url(url(path, query)).post(requestBody).build())
    }

    /** [postJson] without a read timeout, see [longClient]. */
    suspend fun postJsonLong(
        path: String,
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonElement? = withContext(Dispatchers.IO) {
        val requestBody = (body?.toString() ?: "{}").toRequestBody(jsonMediaType)
        execute(Request.Builder().url(url(path, query)).post(requestBody).build(), longClient)
    }

    suspend fun patchJson(
        path: String,
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonElement? = withContext(Dispatchers.IO) {
        val requestBody = (body?.toString() ?: "{}").toRequestBody(jsonMediaType)
        execute(Request.Builder().url(url(path, query)).patch(requestBody).build())
    }

    suspend fun putJson(
        path: String,
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonElement? = withContext(Dispatchers.IO) {
        val requestBody = (body?.toString() ?: "{}").toRequestBody(jsonMediaType)
        execute(Request.Builder().url(url(path, query)).put(requestBody).build())
    }

    suspend fun deleteJson(path: String, query: Map<String, String> = emptyMap()): JsonElement? =
        withContext(Dispatchers.IO) { execute(Request.Builder().url(url(path, query)).delete().build()) }

    fun sseRequest(path: String, query: Map<String, String> = emptyMap()): Request =
        Request.Builder()
            .url(url(path, query))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

    private fun execute(request: Request, httpClient: OkHttpClient = client): JsonElement? {
        val started = System.currentTimeMillis()
        try {
            httpClient.newCall(request).execute().use { response ->
                if (BuildConfig.DEBUG) {
                    Log.d(
                        LOG_TAG,
                        "${request.method} ${Redaction.url(request.url.encodedPath)} -> ${response.code} " +
                            "(${System.currentTimeMillis() - started} ms)",
                    )
                }
                if (!response.isSuccessful) throw errorFor(response)
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                return OpencodeJson.parseToJsonElement(body)
            }
        } catch (api: ApiException) {
            throw api
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) Log.d(LOG_TAG, "${request.method} ${request.url.encodedPath} failed: ${t.javaClass.simpleName}")
            throw ApiException(null, NetworkErrors.describe(t), cause = t)
        }
    }

    private fun errorFor(response: Response): ApiException {
        val rawBody = try {
            response.body?.string()
        } catch (_: Throwable) {
            null
        }
        val preview = rawBody?.let { Redaction.preview(it) }
        val serverMessage = rawBody?.let(::extractServerMessage)
        val message = serverMessage ?: "HTTP ${response.code} ${response.message}".trim()
        return when (response.code) {
            401, 403 -> UnauthorizedException(message)
            404 -> NotFoundException(message)
            else -> ApiException(response.code, message, preview)
        }
    }

    private fun extractServerMessage(body: String): String? {
        val obj: JsonObject = parseJsonObjectOrNull(body) ?: return null
        return obj.str("data", "message")
            ?: obj.str("message")
            ?: obj.str("data", "_tag")
            ?: obj.str("_tag")
            ?: obj.str("data", "error", "message")
    }

    private class AuthInterceptor(private val credentials: ServerCredentials) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.header("Authorization") != null) return chain.proceed(request)
            val builder = request.newBuilder()
            when (credentials.authMode) {
                ai.opencode.android.settings.AuthMode.BASIC -> {
                    builder.header(
                        "Authorization",
                        Credentials.basic(credentials.username.ifBlank { "opencode" }, credentials.password.orEmpty()),
                    )
                }

                ai.opencode.android.settings.AuthMode.BEARER -> {
                    val token = credentials.token
                    if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
                }

                ai.opencode.android.settings.AuthMode.NONE -> Unit
            }
            return chain.proceed(builder.build())
        }
    }

    private class NetworkPolicyInterceptor(private val allowPrivateNetworkCleartext: Boolean) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            when (val result = NetworkPolicy.normalize(url.toString(), allowPrivateNetworkCleartext)) {
                is NetworkPolicy.Result.Allowed -> Unit
                is NetworkPolicy.Result.Rejected -> throw ApiException(null, result.reason)
            }
            return chain.proceed(chain.request())
        }
    }

    /** Logs only method/path/status/duration: never headers, bodies or query strings. */
    private class MetadataLoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val started = System.currentTimeMillis()
            val response = chain.proceed(chain.request())
            Log.d(
                LOG_TAG,
                "${chain.request().method} ${chain.request().url.encodedPath} -> ${response.code} " +
                    "(${System.currentTimeMillis() - started} ms)",
            )
            return response
        }
    }

    private companion object {
        const val LOG_TAG = "opencode-http"
    }
}
