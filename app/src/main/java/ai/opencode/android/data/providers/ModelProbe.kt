package ai.opencode.android.data.providers

import ai.opencode.android.security.NetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A model an OpenAI-compatible endpoint lists under `GET /models`. */
data class RemoteModel(val id: String, val ownedBy: String? = null, val contextLength: Long? = null)

/** Outcome of one tiny chat completion against a model. */
data class ModelTest(val ok: Boolean, val millis: Long, val reply: String? = null, val error: String? = null)

/**
 * Talks to an OpenAI-compatible endpoint the way opencode's `@ai-sdk/openai-compatible` will:
 * lists its models and sends each a one-line prompt, so the user adds only models that answer.
 * Runs from the app (through the app's proxy setting); the key never leaves the request.
 */
class ModelProbe(client: OkHttpClient) {

    private val client = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun listModels(baseUrl: String, apiKey: String): List<RemoteModel> = withContext(Dispatchers.IO) {
        val request = request(baseUrl, "models", apiKey).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(describe(response.code, body))
            parseModels(body)
        }
    }

    suspend fun test(baseUrl: String, apiKey: String, model: String): ModelTest = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", PROMPT)
                }
            }
            put("max_tokens", MAX_TOKENS)
            put("stream", false)
        }
        val request = request(baseUrl, "chat/completions", apiKey)
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()
        val started = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val millis = (System.nanoTime() - started) / 1_000_000
                if (!response.isSuccessful) return@withContext ModelTest(false, millis, error = describe(response.code, body))
                val reply = parseReply(body)
                if (reply == null) {
                    ModelTest(false, millis, error = "no answer in the response: ${body.take(160)}")
                } else {
                    ModelTest(true, millis, reply = reply)
                }
            }
        } catch (e: IOException) {
            ModelTest(false, (System.nanoTime() - started) / 1_000_000, error = e.message ?: e.javaClass.simpleName)
        }
    }

    private fun request(baseUrl: String, path: String, apiKey: String): Request.Builder {
        val url = endpoint(baseUrl, path)
        when (val policy = NetworkPolicy.normalize(url, allowPrivateNetworkCleartext = true)) {
            is NetworkPolicy.Result.Allowed -> Unit
            is NetworkPolicy.Result.Rejected -> throw IOException(policy.reason)
        }
        return Request.Builder().url(url).header("Accept", "application/json").apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}")
        }
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val PROMPT = "Reply with the single word: ok"
        private const val MAX_TOKENS = 16

        /** `https://x/v1` + `models` → `https://x/v1/models`, whatever slashes the user typed. */
        fun endpoint(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + "/" + path

        /** OpenAI (`{data: [{id}]}`), bare arrays, and Ollama-style `{models: [{name}]}`. */
        fun parseModels(body: String): List<RemoteModel> {
            val root = runCatching { JSON.parseToJsonElement(body) }.getOrNull()
                ?: throw IOException("the endpoint did not return JSON: ${body.take(120)}")
            val items = when (root) {
                is JsonArray -> root
                is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: JsonArray(emptyList())
                else -> JsonArray(emptyList())
            }
            return items.mapNotNull { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull?.let { RemoteModel(it) }
                    is JsonObject -> {
                        val id = item.string("id") ?: item.string("name") ?: item.string("model") ?: return@mapNotNull null
                        RemoteModel(
                            id = id,
                            ownedBy = item.string("owned_by"),
                            contextLength = (item["context_length"] as? JsonPrimitive)?.longOrNull
                                ?: (item["max_model_len"] as? JsonPrimitive)?.longOrNull
                                ?: ((item["top_provider"] as? JsonObject)?.get("context_length") as? JsonPrimitive)?.longOrNull,
                        )
                    }
                    else -> null
                }
            }.distinctBy { it.id }.sortedBy { it.id.lowercase() }
        }

        /** The answer text; reasoning models may put everything in `reasoning_content`. */
        fun parseReply(body: String): String? {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val message = choice["message"] as? JsonObject
            val text = message?.string("content")?.takeIf { it.isNotBlank() }
                ?: message?.string("reasoning_content")?.takeIf { it.isNotBlank() }
                ?: message?.string("reasoning")?.takeIf { it.isNotBlank() }
                ?: choice.string("text")?.takeIf { it.isNotBlank() }
            // A model that only spent its tokens thinking still answered: the call itself works.
            return text?.trim()?.take(200) ?: if (message != null) "" else null
        }

        /** `HTTP 401: Invalid API key` from OpenAI-style, plain-string and FastAPI error bodies. */
        fun describe(code: Int, body: String): String {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull()
            val error = root?.get("error")
            val message = (error as? JsonObject)?.string("message")
                ?: (error as? JsonPrimitive)?.contentOrNull
                ?: root?.string("message")
                ?: root?.string("detail")
                ?: body.trim().take(160).ifBlank { null }
            return if (message == null) "HTTP $code" else "HTTP $code: $message"
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
