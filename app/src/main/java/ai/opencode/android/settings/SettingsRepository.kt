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
                palette = this[KEY_THEME_PALETTE]?.toEnum<ThemePalette>() ?: ThemePalette.CLAUDE,
                pureBlack = this[KEY_PURE_BLACK] ?: false,
                chatTextScale = this[KEY_CHAT_TEXT_SCALE]?.toEnum<TextScale>() ?: TextScale.DEFAULT,
            ),
            notifications = this[KEY_NOTIFICATIONS] ?: true,
            systemPrompts = ProfileCodec.decodePrompts(this[KEY_SYSTEM_PROMPTS]),
            activeSystemPromptId = this[KEY_ACTIVE_SYSTEM_PROMPT]?.takeIf { it.isNotBlank() },
            serverProfiles = ProfileCodec.decodeServers(this[KEY_SERVER_PROFILES]),
            opencodeAutoUpdate = this[KEY_OPENCODE_AUTO_UPDATE] ?: true,
            opencodeLastUpdateCheck = this[KEY_OPENCODE_LAST_CHECK] ?: 0L,
            chat = ChatSettings(
                style = this[KEY_CHAT_STYLE]?.toEnum<ChatStyle>() ?: ChatStyle.CLAUDE_CODE,
                monospace = this[KEY_CHAT_MONOSPACE] ?: false,
                expandReasoning = this[KEY_CHAT_EXPAND_REASONING] ?: false,
                expandTools = this[KEY_CHAT_EXPAND_TOOLS] ?: false,
                toolPreviewLines = (this[KEY_CHAT_TOOL_LINES] ?: 3).coerceIn(0, 20),
                sendOnEnter = this[KEY_CHAT_SEND_ON_ENTER] ?: false,
                showTimestamps = this[KEY_CHAT_TIMESTAMPS] ?: true,
                showCost = this[KEY_CHAT_COST] ?: true,
                contextWarnPercent = (this[KEY_CHAT_WARN] ?: 70).coerceIn(10, 99),
                contextDangerPercent = (this[KEY_CHAT_DANGER] ?: 90).coerceIn(10, 100),
                autoCompactPercent = (this[KEY_CHAT_AUTO_COMPACT] ?: 0).coerceIn(0, 99),
                haptics = this[KEY_CHAT_HAPTICS] ?: true,
                playfulSpinner = this[KEY_CHAT_PLAYFUL] ?: true,
            ),
            phone = PhoneAccessSettings(
                storage = this[KEY_PHONE_STORAGE] ?: false,
                androidTools = this[KEY_PHONE_TOOLS] ?: true,
            ),
            modelContextLimits = ProfileCodec.decodeLimits(this[KEY_MODEL_LIMITS]),
            sessionContextLimits = ProfileCodec.decodeLimits(this[KEY_SESSION_LIMITS]),
        ).migrated(this[KEY_UI_REVISION] ?: 0)
    }

    /** One-time defaults for installs that predate a UI revision. */
    private fun AppSettings.migrated(revision: Int): AppSettings {
        if (revision >= UI_REVISION) return this
        // 1.4 made the Claude Code look the default; keep any palette the user picked on purpose.
        val palette = if (appearance.palette == ThemePalette.OPENCODE) ThemePalette.CLAUDE else appearance.palette
        return copy(appearance = appearance.copy(palette = palette))
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
        this[KEY_NOTIFICATIONS] = value.notifications
        this[KEY_SYSTEM_PROMPTS] = ProfileCodec.encodePrompts(value.systemPrompts)
        this[KEY_ACTIVE_SYSTEM_PROMPT] = value.activeSystemPromptId.orEmpty()
        this[KEY_SERVER_PROFILES] = ProfileCodec.encodeServers(value.serverProfiles)
        this[KEY_OPENCODE_AUTO_UPDATE] = value.opencodeAutoUpdate
        this[KEY_OPENCODE_LAST_CHECK] = value.opencodeLastUpdateCheck
        this[KEY_CHAT_STYLE] = value.chat.style.name
        this[KEY_CHAT_MONOSPACE] = value.chat.monospace
        this[KEY_CHAT_EXPAND_REASONING] = value.chat.expandReasoning
        this[KEY_CHAT_EXPAND_TOOLS] = value.chat.expandTools
        this[KEY_CHAT_TOOL_LINES] = value.chat.toolPreviewLines
        this[KEY_CHAT_SEND_ON_ENTER] = value.chat.sendOnEnter
        this[KEY_CHAT_TIMESTAMPS] = value.chat.showTimestamps
        this[KEY_CHAT_COST] = value.chat.showCost
        this[KEY_CHAT_WARN] = value.chat.contextWarnPercent
        this[KEY_CHAT_DANGER] = value.chat.contextDangerPercent
        this[KEY_CHAT_AUTO_COMPACT] = value.chat.autoCompactPercent
        this[KEY_CHAT_HAPTICS] = value.chat.haptics
        this[KEY_CHAT_PLAYFUL] = value.chat.playfulSpinner
        this[KEY_PHONE_STORAGE] = value.phone.storage
        this[KEY_PHONE_TOOLS] = value.phone.androidTools
        this[KEY_MODEL_LIMITS] = ProfileCodec.encodeLimits(value.modelContextLimits)
        this[KEY_SESSION_LIMITS] = ProfileCodec.encodeLimits(value.sessionContextLimits)
        this[KEY_UI_REVISION] = UI_REVISION
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
        val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications")
        val KEY_SYSTEM_PROMPTS = stringPreferencesKey("system_prompts")
        val KEY_ACTIVE_SYSTEM_PROMPT = stringPreferencesKey("active_system_prompt")
        val KEY_SERVER_PROFILES = stringPreferencesKey("server_profiles")
        val KEY_OPENCODE_AUTO_UPDATE = booleanPreferencesKey("opencode_auto_update")
        val KEY_OPENCODE_LAST_CHECK = androidx.datastore.preferences.core.longPreferencesKey("opencode_last_update_check")

        val KEY_CHAT_STYLE = stringPreferencesKey("chat_style")
        val KEY_CHAT_MONOSPACE = booleanPreferencesKey("chat_monospace")
        val KEY_CHAT_EXPAND_REASONING = booleanPreferencesKey("chat_expand_reasoning")
        val KEY_CHAT_EXPAND_TOOLS = booleanPreferencesKey("chat_expand_tools")
        val KEY_CHAT_TOOL_LINES = intPreferencesKey("chat_tool_lines")
        val KEY_CHAT_SEND_ON_ENTER = booleanPreferencesKey("chat_send_on_enter")
        val KEY_CHAT_TIMESTAMPS = booleanPreferencesKey("chat_timestamps")
        val KEY_CHAT_COST = booleanPreferencesKey("chat_cost")
        val KEY_CHAT_WARN = intPreferencesKey("chat_context_warn")
        val KEY_CHAT_DANGER = intPreferencesKey("chat_context_danger")
        val KEY_CHAT_AUTO_COMPACT = intPreferencesKey("chat_auto_compact")
        val KEY_CHAT_HAPTICS = booleanPreferencesKey("chat_haptics")
        val KEY_CHAT_PLAYFUL = booleanPreferencesKey("chat_playful_spinner")
        val KEY_PHONE_STORAGE = booleanPreferencesKey("phone_storage")
        val KEY_PHONE_TOOLS = booleanPreferencesKey("phone_android_tools")
        val KEY_MODEL_LIMITS = stringPreferencesKey("model_context_limits")
        val KEY_SESSION_LIMITS = stringPreferencesKey("session_context_limits")
        val KEY_UI_REVISION = intPreferencesKey("ui_revision")

        /** Bump when [migrated] gains a step. */
        const val UI_REVISION = 14

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
