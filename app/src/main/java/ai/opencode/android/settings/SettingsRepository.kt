package ai.opencode.android.settings

import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionPolicy
import androidx.datastore.preferences.core.MutablePreferences
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
            connectionMode = this[KEY_CONNECTION_MODE]?.toEnum<ConnectionMode>()
                ?: if (this[KEY_SERVER_URL] != null) ConnectionMode.REMOTE else ConnectionMode.LOCAL,
            serverUrl = this[KEY_SERVER_URL] ?: AppSettings.DEFAULT_SERVER_URL,
            authMode = this[KEY_AUTH_MODE]?.toEnum<AuthMode>() ?: AuthMode.NONE,
            username = this[KEY_USERNAME] ?: AppSettings.DEFAULT_USERNAME,
            allowPrivateNetworkCleartext = this[KEY_ALLOW_LAN_CLEARTEXT] ?: true,
            model = if (providerID.isNotBlank() && modelID.isNotBlank()) ModelRef(providerID, modelID) else null,
            agent = this[KEY_AGENT].orEmpty(),
            permissionPolicy = this[KEY_PERMISSION_POLICY]?.toEnum<PermissionPolicy>() ?: PermissionPolicy.ASK,
            lastSessionId = this[KEY_LAST_SESSION]?.takeIf { it.isNotBlank() },
            localServer = LocalServerSettings(
                enabled = this[KEY_LOCAL_SERVER_ENABLED] ?: true,
                port = this[KEY_LOCAL_PORT] ?: LocalServerSettings.DEFAULT_PORT,
                artifactUrl = this[KEY_LOCAL_ARTIFACT_URL].orEmpty(),
                artifactSha256 = this[KEY_LOCAL_ARTIFACT_SHA].orEmpty(),
                artifactVersion = this[KEY_LOCAL_ARTIFACT_VERSION].orEmpty(),
            ),
            appearance = AppearanceSettings(
                themeMode = this[KEY_THEME_MODE]?.toEnum<ThemeMode>() ?: ThemeMode.SYSTEM,
                palette = this[KEY_THEME_PALETTE]?.toEnum<ThemePalette>() ?: ThemePalette.OPENCODE,
                pureBlack = this[KEY_PURE_BLACK] ?: false,
                chatTextScale = this[KEY_CHAT_TEXT_SCALE]?.toEnum<TextScale>() ?: TextScale.DEFAULT,
            ),
        )
    }

    private fun MutablePreferences.write(value: AppSettings) {
        this[KEY_CONNECTION_MODE] = value.connectionMode.name
        this[KEY_SERVER_URL] = value.serverUrl
        this[KEY_AUTH_MODE] = value.authMode.name
        this[KEY_USERNAME] = value.username
        this[KEY_ALLOW_LAN_CLEARTEXT] = value.allowPrivateNetworkCleartext
        this[KEY_MODEL_PROVIDER] = value.model?.providerID.orEmpty()
        this[KEY_MODEL_ID] = value.model?.modelID.orEmpty()
        this[KEY_AGENT] = value.agent
        this[KEY_PERMISSION_POLICY] = value.permissionPolicy.name
        this[KEY_LAST_SESSION] = value.lastSessionId.orEmpty()
        this[KEY_LOCAL_SERVER_ENABLED] = value.localServer.enabled
        this[KEY_LOCAL_PORT] = value.localServer.port
        this[KEY_LOCAL_ARTIFACT_URL] = value.localServer.artifactUrl
        this[KEY_LOCAL_ARTIFACT_SHA] = value.localServer.artifactSha256
        this[KEY_LOCAL_ARTIFACT_VERSION] = value.localServer.artifactVersion
        this[KEY_THEME_MODE] = value.appearance.themeMode.name
        this[KEY_THEME_PALETTE] = value.appearance.palette.name
        this[KEY_PURE_BLACK] = value.appearance.pureBlack
        this[KEY_CHAT_TEXT_SCALE] = value.appearance.chatTextScale.name
        // Settings that no longer exist: drop them so old installs do not carry stale overrides.
        OBSOLETE_KEYS.forEach { remove(it) }
    }

    private inline fun <reified T : Enum<T>> String.toEnum(): T? =
        runCatching { enumValueOf<T>(this) }.getOrNull()

    private companion object {
        val KEY_CONNECTION_MODE = stringPreferencesKey("connection_mode")
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_AUTH_MODE = stringPreferencesKey("auth_mode")
        val KEY_USERNAME = stringPreferencesKey("auth_username")
        val KEY_ALLOW_LAN_CLEARTEXT = booleanPreferencesKey("allow_lan_cleartext")
        val KEY_MODEL_PROVIDER = stringPreferencesKey("model_provider")
        val KEY_MODEL_ID = stringPreferencesKey("model_id")
        val KEY_AGENT = stringPreferencesKey("agent")
        val KEY_PERMISSION_POLICY = stringPreferencesKey("permission_policy")
        val KEY_LAST_SESSION = stringPreferencesKey("last_session_id")
        val KEY_LOCAL_SERVER_ENABLED = booleanPreferencesKey("local_server_enabled")
        val KEY_LOCAL_PORT = intPreferencesKey("local_server_port")
        val KEY_LOCAL_ARTIFACT_URL = stringPreferencesKey("local_artifact_url")
        val KEY_LOCAL_ARTIFACT_SHA = stringPreferencesKey("local_artifact_sha256")
        val KEY_LOCAL_ARTIFACT_VERSION = stringPreferencesKey("local_artifact_version")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
        val KEY_PURE_BLACK = booleanPreferencesKey("theme_pure_black")
        val KEY_CHAT_TEXT_SCALE = stringPreferencesKey("chat_text_scale")

        val OBSOLETE_KEYS = listOf(
            booleanPreferencesKey("auto_connect"),
            intPreferencesKey("request_timeout_seconds"),
            stringPreferencesKey("local_server_rootfs"),
            stringPreferencesKey("local_server_proot"),
            stringPreferencesKey("local_server_command"),
            booleanPreferencesKey("local_auto_restart"),
            intPreferencesKey("local_max_restarts"),
        )
    }
}
