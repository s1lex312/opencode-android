package ai.opencode.android.settings

import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionPolicy
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "opencode_settings")

/**
 * Persists non-secret configuration. DataStore writes are transactional and survive process
 * death, which is what the "settings must be remembered between launches" requirement needs.
 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = store.data
        .map { it.toAppSettings() }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toAppSettings())
            prefs.write(updated)
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val providerID = this[KEY_MODEL_PROVIDER].orEmpty()
        val modelID = this[KEY_MODEL_ID].orEmpty()
        return AppSettings(
            connectionMode = this[KEY_CONNECTION_MODE]?.toConnectionMode()
                ?: if (this[KEY_SERVER_URL] != null) ConnectionMode.REMOTE else ConnectionMode.LOCAL,
            serverUrl = this[KEY_SERVER_URL] ?: AppSettings.DEFAULT_SERVER_URL,
            authMode = this[KEY_AUTH_MODE]?.toAuthMode() ?: AuthMode.NONE,
            username = this[KEY_USERNAME] ?: "opencode",
            allowPrivateNetworkCleartext = this[KEY_ALLOW_LAN_CLEARTEXT] ?: true,
            autoConnect = this[KEY_AUTO_CONNECT] ?: true,
            model = if (providerID.isNotBlank() && modelID.isNotBlank()) ModelRef(providerID, modelID) else null,
            agent = this[KEY_AGENT].orEmpty(),
            permissionPolicy = this[KEY_PERMISSION_POLICY]?.toPermissionPolicy() ?: PermissionPolicy.ASK,
            requestTimeoutSeconds = this[KEY_TIMEOUT] ?: 300,
            lastSessionId = this[KEY_LAST_SESSION]?.takeIf { it.isNotBlank() },
            localServer = LocalServerSettings(
                enabled = this[KEY_LOCAL_SERVER_ENABLED] ?: true,
                port = this[KEY_LOCAL_PORT] ?: 4096,
                serverCommand = this[KEY_LOCAL_COMMAND]
                    ?: "opencode serve --hostname 127.0.0.1 --port {port}",
                rootfsPath = this[KEY_LOCAL_ROOTFS].orEmpty(),
                prootPath = this[KEY_LOCAL_PROOT].orEmpty(),
                artifactUrl = this[KEY_LOCAL_ARTIFACT_URL].orEmpty(),
                artifactSha256 = this[KEY_LOCAL_ARTIFACT_SHA].orEmpty(),
                artifactVersion = this[KEY_LOCAL_ARTIFACT_VERSION].orEmpty(),
                autoRestart = this[KEY_LOCAL_AUTO_RESTART] ?: true,
                maxRestarts = this[KEY_LOCAL_MAX_RESTARTS] ?: 5,
            ),
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(value: AppSettings) {
        this[KEY_CONNECTION_MODE] = value.connectionMode.name
        this[KEY_SERVER_URL] = value.serverUrl
        this[KEY_AUTH_MODE] = value.authMode.name
        this[KEY_USERNAME] = value.username
        this[KEY_ALLOW_LAN_CLEARTEXT] = value.allowPrivateNetworkCleartext
        this[KEY_AUTO_CONNECT] = value.autoConnect
        this[KEY_MODEL_PROVIDER] = value.model?.providerID.orEmpty()
        this[KEY_MODEL_ID] = value.model?.modelID.orEmpty()
        this[KEY_AGENT] = value.agent
        this[KEY_PERMISSION_POLICY] = value.permissionPolicy.name
        this[KEY_TIMEOUT] = value.requestTimeoutSeconds
        this[KEY_LAST_SESSION] = value.lastSessionId.orEmpty()
        this[KEY_LOCAL_SERVER_ENABLED] = value.localServer.enabled
        this[KEY_LOCAL_ROOTFS] = value.localServer.rootfsPath
        this[KEY_LOCAL_PROOT] = value.localServer.prootPath
        this[KEY_LOCAL_PORT] = value.localServer.port
        this[KEY_LOCAL_COMMAND] = value.localServer.serverCommand
        this[KEY_LOCAL_ARTIFACT_URL] = value.localServer.artifactUrl
        this[KEY_LOCAL_ARTIFACT_SHA] = value.localServer.artifactSha256
        this[KEY_LOCAL_ARTIFACT_VERSION] = value.localServer.artifactVersion
        this[KEY_LOCAL_AUTO_RESTART] = value.localServer.autoRestart
        this[KEY_LOCAL_MAX_RESTARTS] = value.localServer.maxRestarts
    }

    private fun String.toConnectionMode(): ConnectionMode =
        runCatching { ConnectionMode.valueOf(this) }.getOrDefault(ConnectionMode.LOCAL)

    private fun String.toAuthMode(): AuthMode = runCatching { AuthMode.valueOf(this) }.getOrDefault(AuthMode.NONE)

    private fun String.toPermissionPolicy(): PermissionPolicy =
        runCatching { PermissionPolicy.valueOf(this) }.getOrDefault(PermissionPolicy.ASK)

    private companion object {
        val KEY_CONNECTION_MODE = stringPreferencesKey("connection_mode")
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_AUTH_MODE = stringPreferencesKey("auth_mode")
        val KEY_USERNAME = stringPreferencesKey("auth_username")
        val KEY_ALLOW_LAN_CLEARTEXT = booleanPreferencesKey("allow_lan_cleartext")
        val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val KEY_MODEL_PROVIDER = stringPreferencesKey("model_provider")
        val KEY_MODEL_ID = stringPreferencesKey("model_id")
        val KEY_AGENT = stringPreferencesKey("agent")
        val KEY_PERMISSION_POLICY = stringPreferencesKey("permission_policy")
        val KEY_TIMEOUT = intPreferencesKey("request_timeout_seconds")
        val KEY_LAST_SESSION = stringPreferencesKey("last_session_id")
        val KEY_LOCAL_SERVER_ENABLED = booleanPreferencesKey("local_server_enabled")
        val KEY_LOCAL_ROOTFS = stringPreferencesKey("local_server_rootfs")
        val KEY_LOCAL_PROOT = stringPreferencesKey("local_server_proot")
        val KEY_LOCAL_PORT = intPreferencesKey("local_server_port")
        val KEY_LOCAL_COMMAND = stringPreferencesKey("local_server_command")
        val KEY_LOCAL_ARTIFACT_URL = stringPreferencesKey("local_artifact_url")
        val KEY_LOCAL_ARTIFACT_SHA = stringPreferencesKey("local_artifact_sha256")
        val KEY_LOCAL_ARTIFACT_VERSION = stringPreferencesKey("local_artifact_version")
        val KEY_LOCAL_AUTO_RESTART = booleanPreferencesKey("local_auto_restart")
        val KEY_LOCAL_MAX_RESTARTS = intPreferencesKey("local_max_restarts")
    }
}
