package ai.opencode.android.bridge

import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.McpConfig
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.security.SecretStore
import ai.opencode.android.settings.SettingsRepository
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** Shared storage ("All files access" on Android 11+, the storage permission before). */
object StorageAccess {

    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** Settings screen to grant access on Android 11+; `null` means "request the runtime permission". */
    fun settingsIntent(context: Context): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    } else {
        null
    }

    /** proot `-b` arguments that expose shared storage at `/sdcard` and `/storage`. */
    fun binds(context: Context): List<String> {
        if (!granted(context)) return emptyList()
        val primary = Environment.getExternalStorageDirectory().absolutePath
        return listOf("-b", "/storage", "-b", "$primary:/sdcard")
    }
}

data class PhoneAccessState(
    val toolsRunning: Boolean = false,
    val port: Int? = null,
    /** opencode has the `android` MCP server in its config. */
    val registered: Boolean = false,
    val error: String? = null,
)

/**
 * Runs the Android tools MCP server while Local mode and the setting are on, and keeps opencode's
 * global config pointing at it (`mcp.android`).
 */
class PhoneAccessManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val connection: ServerConnectionManager,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope,
) {
    val tools = AndroidTools(context)
    private val server = AndroidToolsServer(tools) { token() }
    private val _state = MutableStateFlow(PhoneAccessState())
    val state: StateFlow<PhoneAccessState> = _state.asStateFlow()

    /** Server instance (baseUrl) the current registration was written to. */
    private var registeredFor: String? = null

    fun start() {
        scope.launch {
            combine(settings.settings, connection.state) { s, c -> Triple(s.isLocalMode, s.phone.androidTools, c) }
                .distinctUntilChanged()
                .collect { (local, enabled, connectionState) ->
                    val wanted = local && enabled
                    if (wanted) startServer() else stopServer()
                    val connected = connectionState as? ConnectionState.Connected ?: return@collect
                    if (!local) return@collect
                    if (wanted && registeredFor != connected.baseUrl) register(connected.baseUrl)
                    if (!wanted && _state.value.registered) unregister()
                }
        }
    }

    private suspend fun startServer() = withContext(Dispatchers.IO) {
        if (server.port != null) return@withContext
        try {
            val port = server.start(AndroidToolsServer.DEFAULT_PORT)
            _state.update { it.copy(toolsRunning = true, port = port, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(toolsRunning = false, port = null, error = e.message) }
        }
    }

    private fun stopServer() {
        if (server.port == null) return
        server.stop()
        _state.update { it.copy(toolsRunning = false, port = null) }
    }

    private suspend fun register(baseUrl: String) {
        val port = server.port ?: return
        val config = McpConfig.Remote(
            url = "http://127.0.0.1:$port${AndroidToolsServer.PATH}",
            headers = mapOf("Authorization" to "Bearer ${token()}"),
            oauth = false,
            enabled = true,
        )
        when (val result = sessions.mcpSave(MCP_NAME, config)) {
            is ApiResult.Ok -> {
                registeredFor = baseUrl
                _state.update { it.copy(registered = true, error = null) }
            }

            is ApiResult.Err -> {
                Log.w(TAG, "registering the android MCP server failed: ${result.message}")
                _state.update { it.copy(registered = false, error = result.message) }
            }
        }
    }

    private suspend fun unregister() {
        val port = _state.value.port ?: AndroidToolsServer.DEFAULT_PORT
        val disabled = McpConfig.Remote(
            url = "http://127.0.0.1:$port${AndroidToolsServer.PATH}",
            headers = mapOf("Authorization" to "Bearer ${token()}"),
            oauth = false,
            enabled = false,
        )
        if (sessions.mcpSave(MCP_NAME, disabled) is ApiResult.Ok) {
            registeredFor = null
            _state.update { it.copy(registered = false) }
        }
    }

    /** Stable random bearer token, created on first use. */
    @Synchronized
    private fun token(): String {
        secrets.profileSecret(TOKEN_KEY)?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        secrets.setProfileSecret(TOKEN_KEY, token)
        return token
    }

    companion object {
        const val MCP_NAME = "android"
        private const val TOKEN_KEY = "android-mcp:token"
        private const val TAG = "PhoneAccess"
    }
}
