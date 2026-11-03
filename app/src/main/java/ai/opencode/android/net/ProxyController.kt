package ai.opencode.android.net

import ai.opencode.android.security.SecretStore
import ai.opencode.android.settings.ProxySettings
import ai.opencode.android.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/** One row of the proxy test: an API host and what it answered through the current routing. */
data class ProxyProbe(
    val name: String,
    val url: String,
    val proxied: Boolean,
    val code: Int? = null,
    val millis: Long = 0,
    val error: String? = null,
) {
    /** 401/404 mean "reachable, no key"; 403 from these hosts is the region block. */
    val reachable: Boolean get() = code != null && code != 403 && code < 500

    val regionBlocked: Boolean get() = code == 403
}

data class ProxyState(
    val settings: ProxySettings = ProxySettings(),
    val bridgePort: Int = 0,
    /** Port the running in-app server was started with; differs from [bridgePort] → restart needed. */
    val serverPort: Int? = null,
    val testing: Boolean = false,
    val probes: List<ProxyProbe> = emptyList(),
    val error: String? = null,
)

/**
 * Owns the local proxy bridge and decides who uses it:
 *  * the in-app server through [serverEnvironment] (read once per server launch);
 *  * the app's own OkHttp clients through [AppProxySelector].
 */
class ProxyController(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(ProxyState())
    val state: StateFlow<ProxyState> = _state.asStateFlow()

    @Volatile
    private var current: ProxySettings = ProxySettings()

    private val bridge = ProxyBridge { upstream() }

    init {
        AppProxySelector.controller = this
    }

    fun start() {
        scope.launch {
            settings.settings.map { it.proxy }.distinctUntilChanged().collect { apply(it) }
        }
    }

    private fun upstream(): ProxyUpstream? =
        current.takeIf { it.active }?.let { ProxyUpstream.from(it, secrets.profileSecret(ProxySettings.PASSWORD_KEY)) }

    private fun apply(value: ProxySettings) {
        current = value
        // A server started with the proxy keeps pointing at the bridge until it restarts: keep the
        // bridge up (connecting directly, since the upstream is now off) so its requests still work.
        val serverStillUsesBridge = bridge.running && _state.value.serverPort == bridge.port
        when {
            value.active -> runCatching { bridge.start(BRIDGE_PORT) }
            !serverStillUsesBridge -> bridge.stop()
        }
        _state.value = _state.value.copy(settings = value, bridgePort = if (value.active) bridge.port else 0)
    }

    /**
     * Environment for a new in-app server process. Reads the stored settings synchronously because
     * the service can launch the server before [start] has seen the first value.
     */
    fun serverEnvironment(): Map<String, String> {
        val value = runCatching { runBlocking { settings.current().proxy } }.getOrDefault(current)
        if (value != current) apply(value)
        val port = if (value.active && value.applyToServer) bridge.port else 0
        _state.value = _state.value.copy(serverPort = port)
        if (port == 0) return emptyMap()
        val url = "http://127.0.0.1:$port"
        val local = "localhost,127.0.0.1,::1"
        return mapOf(
            "HTTP_PROXY" to url, "HTTPS_PROXY" to url, "ALL_PROXY" to url,
            "http_proxy" to url, "https_proxy" to url, "all_proxy" to url,
            "NO_PROXY" to local, "no_proxy" to local,
            // Node 22.21+/24 fetch only honours the variables above with this switch.
            "NODE_USE_ENV_PROXY" to "1",
        )
    }

    /** Proxy for one app request, or null for a direct connection. */
    internal fun proxyFor(host: String): Proxy? {
        val value = current
        if (!value.active || !value.applyToApp) return null
        val port = bridge.port.takeIf { it != 0 } ?: return null
        if (HostRules.isLocal(host.lowercase())) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
    }

    fun setPassword(password: String?) {
        secrets.setProfileSecret(ProxySettings.PASSWORD_KEY, password?.takeIf { it.isNotEmpty() })
    }

    fun hasPassword(): Boolean = !secrets.profileSecret(ProxySettings.PASSWORD_KEY).isNullOrEmpty()

    suspend fun save(value: ProxySettings) {
        settings.update { it.copy(proxy = value) }
        apply(value)
    }

    /**
     * Calls a few provider APIs the way the in-app server would: through the bridge (which applies
     * the scope rules), or directly when the proxy is off. 401/404 = reachable, 403 = region block.
     */
    fun test() {
        if (_state.value.testing) return
        _state.value = _state.value.copy(testing = true, error = null)
        scope.launch {
            val value = current
            val port = if (value.active) bridge.port else 0
            val client = OkHttpClient.Builder()
                .proxy(if (port != 0) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)) else Proxy.NO_PROXY)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .build()
            val upstream = upstream()
            val probes = withContext(Dispatchers.IO) {
                coroutineScope {
                    TEST_TARGETS.map { (name, url) ->
                        async { probe(client, name, url, upstream?.routes(URI(url).host) == true) }
                    }.awaitAll()
                }
            }
            _state.value = _state.value.copy(testing = false, probes = probes)
        }
    }

    private fun probe(client: OkHttpClient, name: String, url: String, proxied: Boolean): ProxyProbe {
        val started = System.currentTimeMillis()
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                ProxyProbe(name, url, proxied, response.code, System.currentTimeMillis() - started)
            }
        } catch (failure: IOException) {
            ProxyProbe(name, url, proxied, null, System.currentTimeMillis() - started, failure.message ?: failure.javaClass.simpleName)
        }
    }

    companion object {
        /** Fixed so a server started before the app process restarted still finds the bridge. */
        const val BRIDGE_PORT = 18_765

        val TEST_TARGETS = listOf(
            "OpenAI" to "https://api.openai.com/v1/models",
            "Anthropic" to "https://api.anthropic.com/v1/models",
            "Google Gemini" to "https://generativelanguage.googleapis.com/v1beta/models",
            "xAI" to "https://api.x.ai/v1/models",
            "OpenRouter" to "https://openrouter.ai/api/v1/models",
            "GitHub Copilot" to "https://api.githubcopilot.com/models",
        )
    }
}

/**
 * Installed on every OkHttp client of the app. Asks the [ProxyController] per connection, so the
 * clients never need rebuilding when the proxy settings change.
 */
object AppProxySelector : ProxySelector() {

    @Volatile
    internal var controller: ProxyController? = null

    override fun select(uri: URI?): List<Proxy> {
        val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
        return listOf(controller?.proxyFor(host) ?: Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}
