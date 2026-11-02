package ai.opencode.android.data.repo

import ai.opencode.android.data.model.AuthOption
import ai.opencode.android.data.model.AuthPrompt
import ai.opencode.android.data.model.CustomProviderDraft
import ai.opencode.android.data.model.OAuthSession
import ai.opencode.android.data.model.ProviderAuthMethod
import ai.opencode.android.data.model.ProviderInfo
import ai.opencode.android.data.remote.ApiException
import ai.opencode.android.data.remote.ServerHttp
import ai.opencode.android.data.remote.arr
import ai.opencode.android.data.remote.obj
import ai.opencode.android.data.remote.objects
import ai.opencode.android.data.remote.str
import ai.opencode.android.data.remote.strList
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Connects AI providers on the opencode server — the app never talks to the providers itself.
 *
 * Two server generations are supported:
 *  * **1.x** (what the bundled environment runs): `GET /provider`, `GET /provider/auth`,
 *    `PUT|DELETE /auth/{id}` into `auth.json`, OAuth via `/provider/{id}/oauth/authorize|callback`,
 *    custom providers via `PATCH /global/config`. Provider state is cached per instance, so every
 *    change ends with `POST /instance/dispose`, exactly like the official TUI. 1.16–1.19 also serve
 *    the v2 `/api/...` routes with their own credential table, so keys are written to both stores.
 *  * **2.x**: only `/api/...` — `/api/integration` lists providers and sign-in methods,
 *    `/api/integration/{id}/connect/key|oauth` connects, `DELETE /api/credential/{id}` disconnects.
 */
class ProviderRepository(private val connection: ServerConnectionManager) {

    /** Remembered per server: whether the legacy 1.x routes answer. */
    private var legacyFor: Pair<String, Boolean>? = null

    suspend fun providers(): ApiResult<List<ProviderInfo>> {
        val http = connection.api?.http ?: return notConnected()
        return runApiCall { if (isLegacy(http)) legacyProviders(http) else v2Providers(http) }
    }

    suspend fun setApiKey(providerId: String, key: String): ApiResult<Unit> {
        val http = connection.api?.http ?: return notConnected()
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ApiResult.Err("The API key is empty")
        return runApiCall {
            if (isLegacy(http)) {
                http.putJson(
                    "/auth/${enc(providerId)}",
                    buildJsonObject {
                        put("type", "api")
                        put("key", trimmed)
                    },
                )
                // Sessions on the /api routes of 1.16+ read a separate credential store.
                runCatching { v2ConnectKey(http, providerId, trimmed) }
                reload(http, legacy = true)
            } else {
                v2ConnectKey(http, providerId, trimmed)
                reload(http, legacy = false)
            }
        }
    }

    suspend fun disconnect(provider: ProviderInfo): ApiResult<Unit> {
        val http = connection.api?.http ?: return notConnected()
        return runApiCall {
            if (isLegacy(http)) {
                http.deleteJson("/auth/${enc(provider.id)}")
                runCatching { v2Credentials(http, provider.id).forEach { http.deleteJson("/api/credential/${enc(it)}") } }
                runCatching { http.postJson("/global/dispose") }
            } else {
                provider.credentialIds.forEach { http.deleteJson("/api/credential/${enc(it)}") }
                reload(http, legacy = false)
            }
        }
    }

    /** Starts a browser sign-in; [inputs] answers the method's prompts by [AuthPrompt.key]. */
    suspend fun startOAuth(
        providerId: String,
        method: ProviderAuthMethod.OAuth,
        inputs: Map<String, String>,
    ): ApiResult<OAuthSession> {
        val http = connection.api?.http ?: return notConnected()
        return runApiCall {
            if (isLegacy(http)) {
                val response = http.postJson(
                    "/provider/${enc(providerId)}/oauth/authorize",
                    buildJsonObject {
                        put("method", method.index)
                        if (inputs.isNotEmpty()) putJsonObject("inputs") { inputs.forEach { (k, v) -> put(k, v) } }
                    },
                ) as? JsonObject ?: throw ApiException(null, "This provider does not offer browser sign-in")
                OAuthSession(
                    providerId = providerId,
                    method = method,
                    url = response.str("url") ?: throw ApiException(null, "The server returned no sign-in URL"),
                    instructions = response.str("instructions"),
                    needsCode = response.str("method") == "code",
                )
            } else {
                val response = http.postJson(
                    "/api/integration/${enc(providerId)}/connect/oauth",
                    buildJsonObject {
                        put("methodID", method.methodId ?: method.index.toString())
                        if (inputs.isNotEmpty()) putJsonObject("answer") { inputs.forEach { (k, v) -> put(k, v) } }
                    },
                ) as? JsonObject
                val data = response?.obj("data") ?: response ?: throw ApiException(null, "Malformed sign-in response")
                OAuthSession(
                    providerId = providerId,
                    method = method,
                    url = data.str("url") ?: throw ApiException(null, "The server returned no sign-in URL"),
                    instructions = data.str("instructions"),
                    needsCode = data.str("mode") == "code",
                    attemptId = data.str("attemptID"),
                )
            }
        }
    }

    /**
     * Finishes a sign-in. For automatic flows (device code, local callback) this waits until the
     * provider confirms — the 1.x server blocks the request, a 2.x attempt is polled.
     */
    suspend fun finishOAuth(session: OAuthSession, code: String?): ApiResult<Unit> {
        val http = connection.api?.http ?: return notConnected()
        return runApiCall {
            if (isLegacy(http)) {
                http.postJson(
                    "/provider/${enc(session.providerId)}/oauth/callback",
                    buildJsonObject {
                        put("method", session.method.index)
                        if (!code.isNullOrBlank()) put("code", code.trim())
                    },
                )
                reload(http, legacy = true)
            } else {
                val attempt = session.attemptId ?: throw ApiException(null, "Sign-in attempt is missing")
                val base = "/api/integration/${enc(session.providerId)}/connect/oauth/${enc(attempt)}"
                if (session.needsCode) {
                    http.postJson("$base/complete", buildJsonObject { put("code", code?.trim().orEmpty()) })
                } else {
                    awaitAttempt(http, base)
                }
                reload(http, legacy = false)
            }
        }
    }

    /** Adds an OpenAI-compatible endpoint to the global config (1.x servers). */
    suspend fun addCustomProvider(draft: CustomProviderDraft): ApiResult<Unit> {
        val http = connection.api?.http ?: return notConnected()
        return runApiCall {
            if (!isLegacy(http)) {
                throw ApiException(null, "This server only accepts custom providers from its config file")
            }
            if (draft.apiKey.isNotBlank()) {
                http.putJson(
                    "/auth/${enc(draft.id)}",
                    buildJsonObject {
                        put("type", "api")
                        put("key", draft.apiKey.trim())
                    },
                )
            }
            // PATCH /global/config merges into opencode.json(c) and reloads every instance itself.
            http.patchJson(
                "/global/config",
                buildJsonObject {
                    putJsonObject("provider") {
                        putJsonObject(draft.id) {
                            put("npm", "@ai-sdk/openai-compatible")
                            put("name", draft.name.ifBlank { draft.id })
                            putJsonObject("options") { put("baseURL", draft.baseUrl.trim()) }
                            putJsonObject("models") {
                                draft.models.forEach { model -> putJsonObject(model) { put("name", model) } }
                            }
                        }
                    }
                },
            )
            Unit
        }
    }

    // ------------------------------------------------------------------ 1.x

    private suspend fun isLegacy(http: ServerHttp): Boolean {
        legacyFor?.let { (server, legacy) -> if (server == http.baseUrl) return legacy }
        // 2.x serves its web UI for unknown GET paths, so only a real provider list counts.
        val legacy = runCatching { (http.getJson("/provider") as? JsonObject)?.containsKey("all") == true }
            .getOrDefault(false)
        legacyFor = http.baseUrl to legacy
        return legacy
    }

    private suspend fun legacyProviders(http: ServerHttp): List<ProviderInfo> {
        val root = http.getJson("/provider") as? JsonObject ?: throw ApiException(null, "Malformed provider list")
        val connected = root.strList("connected").toSet()
        val methods = runCatching { http.getJson("/provider/auth") as? JsonObject }.getOrNull()
        return root.arr("all")?.objects().orEmpty().mapNotNull { provider ->
            val id = provider.str("id") ?: return@mapNotNull null
            ProviderInfo(
                id = id,
                name = provider.str("name") ?: id,
                connected = id in connected,
                source = provider.str("source"),
                env = provider.strList("env"),
                modelCount = provider.obj("models")?.size ?: 0,
                authMethods = (methods?.get(id) as? JsonArray)?.let(::legacyMethods)
                    ?: listOf(ProviderAuthMethod.ApiKey()),
            )
        }
    }

    private fun legacyMethods(array: JsonArray): List<ProviderAuthMethod> =
        array.objects().mapIndexed { index, method ->
            val label = method.str("label")
            if (method.str("type") == "oauth") {
                ProviderAuthMethod.OAuth(
                    label = label,
                    index = index,
                    prompts = method.arr("prompts")?.objects().orEmpty().mapNotNull(::prompt),
                )
            } else {
                ProviderAuthMethod.ApiKey(label)
            }
        }.ifEmpty { listOf(ProviderAuthMethod.ApiKey()) }

    private fun prompt(obj: JsonObject): AuthPrompt? {
        val key = obj.str("key") ?: return null
        return AuthPrompt(
            key = key,
            message = obj.str("message") ?: key,
            placeholder = obj.str("placeholder"),
            options = obj.arr("options")?.objects().orEmpty().mapNotNull { option ->
                val value = option.str("value") ?: return@mapNotNull null
                AuthOption(label = option.str("label") ?: value, value = value, hint = option.str("hint"))
            },
        )
    }

    // ------------------------------------------------------------------ 2.x

    private suspend fun v2Providers(http: ServerHttp): List<ProviderInfo> {
        val response = http.getJson("/api/integration")
        val array = (response as? JsonObject)?.get("data") as? JsonArray ?: response as? JsonArray
            ?: throw ApiException(null, "This server does not expose provider management")
        val modelCounts = runCatching {
            val models = http.getJson("/api/model")
            ((models as? JsonObject)?.get("data") as? JsonArray ?: models as? JsonArray)
                ?.objects().orEmpty().groupingBy { it.str("providerID").orEmpty() }.eachCount()
        }.getOrDefault(emptyMap())
        return array.objects().mapNotNull { integration ->
            val id = integration.str("id") ?: return@mapNotNull null
            val methodObjects = integration.arr("methods")?.objects().orEmpty()
            val connections = integration.arr("connections")?.objects().orEmpty()
            val methods = methodObjects.mapIndexedNotNull { index, method ->
                when (method.str("type")) {
                    "key" -> ProviderAuthMethod.ApiKey(method.str("label"))
                    "oauth" -> ProviderAuthMethod.OAuth(
                        label = method.str("label"),
                        index = index,
                        methodId = method.str("id"),
                        prompts = method.arr("form")?.objects().orEmpty().mapNotNull(::formPrompt),
                    )

                    else -> null
                }
            }
            ProviderInfo(
                id = id,
                name = integration.str("name") ?: id,
                connected = connections.isNotEmpty(),
                source = connections.firstOrNull()?.let { it.str("method") ?: it.str("type") },
                env = methodObjects.filter { it.str("type") == "env" }.flatMap { it.strList("names") },
                modelCount = modelCounts[id] ?: 0,
                authMethods = methods.ifEmpty { listOf(ProviderAuthMethod.ApiKey()) },
                credentialIds = connections.filter { it.str("type") == "credential" }.mapNotNull { it.str("id") },
            )
        }
    }

    private fun formPrompt(field: JsonObject): AuthPrompt? {
        val key = field.str("key") ?: field.str("id") ?: return null
        return AuthPrompt(
            key = key,
            message = field.str("label") ?: field.str("message") ?: key,
            placeholder = field.str("placeholder"),
            options = field.arr("options")?.objects().orEmpty().mapNotNull { option ->
                val value = option.str("value") ?: return@mapNotNull null
                AuthOption(label = option.str("label") ?: value, value = value, hint = option.str("hint"))
            },
        )
    }

    private suspend fun v2ConnectKey(http: ServerHttp, providerId: String, key: String) {
        http.postJson("/api/integration/${enc(providerId)}/connect/key", buildJsonObject { put("key", key) })
    }

    private suspend fun v2Credentials(http: ServerHttp, providerId: String): List<String> {
        val response = http.getJson("/api/credential") as? JsonObject ?: return emptyList()
        return response.arr("data")?.objects().orEmpty()
            .filter { it.str("integrationID") == providerId }
            .mapNotNull { it.str("id") }
    }

    private suspend fun awaitAttempt(http: ServerHttp, path: String) {
        repeat(OAUTH_POLLS) {
            val response = http.getJson(path) as? JsonObject
            val data = response?.obj("data") ?: response
            when (data?.str("status")) {
                "complete" -> return
                "failed" -> throw ApiException(null, data.str("message") ?: "Sign-in failed")
                "expired" -> throw ApiException(null, "The sign-in link expired. Start again.")
            }
            delay(OAUTH_POLL_MS)
        }
        throw ApiException(null, "Sign-in was not completed in time")
    }

    // ------------------------------------------------------------------ shared

    /** Makes the server rebuild its provider state so new credentials take effect immediately. */
    private suspend fun reload(http: ServerHttp, legacy: Boolean) {
        if (legacy) {
            runCatching { http.postJson("/instance/dispose") }
        } else {
            runCatching { http.postJson("/api/location/reload") }
        }
    }

    private fun notConnected(): ApiResult.Err = ApiResult.Err(
        message = "Not connected to an opencode server",
        notConnected = true,
    )

    private companion object {
        const val OAUTH_POLLS = 150
        const val OAUTH_POLL_MS = 2_000L

        fun enc(pathSegment: String): String =
            java.net.URLEncoder.encode(pathSegment, "UTF-8").replace("+", "%20")
    }
}
