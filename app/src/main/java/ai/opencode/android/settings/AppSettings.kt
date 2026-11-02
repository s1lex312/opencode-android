package ai.opencode.android.settings

import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.security.NetworkPolicy

enum class AuthMode { NONE, BASIC, BEARER }

/**
 * Where the app talks to opencode.
 *
 * [LOCAL] is the default: the app provisions and runs `opencode serve` inside its own sandbox via
 * proot and connects to `http://127.0.0.1:<port>`. [REMOTE] keeps the original behaviour of
 * pointing at an external server (laptop, VPS, Termux) over LAN/VPN.
 */
enum class ConnectionMode { LOCAL, REMOTE }

/** Light/dark selection. [SYSTEM] follows the device setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Accent families. [DYNAMIC] is Material You (wallpaper colours, Android 12+); on older devices it
 * falls back to [OPENCODE].
 */
enum class ThemePalette { OPENCODE, DYNAMIC, EMERALD, AMBER, VIOLET, ROSE }

/** Relative size of chat text. */
enum class TextScale(val factor: Float) { SMALL(0.9f), DEFAULT(1f), LARGE(1.15f) }

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.OPENCODE,
    /** True black surfaces in dark mode (OLED). */
    val pureBlack: Boolean = false,
    val chatTextScale: TextScale = TextScale.DEFAULT,
)

data class LocalServerSettings(
    /** Start the local server automatically when the app opens. */
    val enabled: Boolean = true,
    val port: Int = DEFAULT_PORT,
    /** Bundle location; empty means "read local-env.tar.gz from APK assets". */
    val artifactUrl: String = "",
    val artifactSha256: String = "",
    val artifactVersion: String = "",
) {
    val url: String get() = "http://127.0.0.1:${port}"

    /** `opencode serve` bound to loopback only; `{port}` is substituted at launch. */
    val serverCommand: String get() = SERVER_COMMAND

    companion object {
        const val DEFAULT_PORT = 4096
        const val SERVER_COMMAND = "opencode serve --hostname 127.0.0.1 --port {port}"

        /** The supervisor restarts a crashed server with backoff, at most this many times. */
        const val MAX_RESTARTS = 5
    }
}

/**
 * Everything that is persisted in plain DataStore. Secrets (password / token) live in
 * [ai.opencode.android.security.SecretStore] and are never part of this object.
 */
data class AppSettings(
    val connectionMode: ConnectionMode = ConnectionMode.LOCAL,
    val serverUrl: String = DEFAULT_SERVER_URL,
    val authMode: AuthMode = AuthMode.NONE,
    val username: String = DEFAULT_USERNAME,
    val allowPrivateNetworkCleartext: Boolean = true,
    val model: ModelRef? = null,
    /** Agent used for new prompts; blank means the server default (usually `build`). */
    val agent: String = "",
    val permissionPolicy: PermissionPolicy = PermissionPolicy.ASK,
    val lastSessionId: String? = null,
    val localServer: LocalServerSettings = LocalServerSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    /** Notify about permission requests and finished turns while the app is in the background. */
    val notifications: Boolean = true,
    val systemPrompts: List<SystemPromptProfile> = emptyList(),
    /** Id of the active [SystemPromptProfile]; `null` sends no extra system prompt. */
    val activeSystemPromptId: String? = null,
    val serverProfiles: List<ServerProfile> = emptyList(),
    /** Keep the bundled opencode binary up to date from GitHub releases (Local mode). */
    val opencodeAutoUpdate: Boolean = true,
    val opencodeLastUpdateCheck: Long = 0L,
) {
    val activeSystemPrompt: SystemPromptProfile?
        get() = systemPrompts.firstOrNull { it.id == activeSystemPromptId }

    val activeServerProfile: ServerProfile?
        get() = serverProfiles.firstOrNull { it.matches(this) }

    /** True when the configured address passes the cleartext policy. */
    val serverUrlValid: Boolean
        get() = NetworkPolicy.normalize(serverUrl, allowPrivateNetworkCleartext) is NetworkPolicy.Result.Allowed

    /** Address the client actually connects to, given the selected mode. */
    val effectiveServerUrl: String
        get() = if (connectionMode == ConnectionMode.LOCAL) localServer.url else serverUrl

    val effectiveAuthMode: AuthMode
        get() = if (connectionMode == ConnectionMode.LOCAL) AuthMode.BASIC else authMode

    val effectiveUsername: String
        get() = if (connectionMode == ConnectionMode.LOCAL) DEFAULT_USERNAME else username

    val isLocalMode: Boolean get() = connectionMode == ConnectionMode.LOCAL

    /** Long prompts can take minutes before the first byte; SSE has no read timeout at all. */
    val requestTimeoutSeconds: Int get() = REQUEST_TIMEOUT_SECONDS

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"
        const val DEFAULT_USERNAME = "opencode"
        const val REQUEST_TIMEOUT_SECONDS = 300
    }
}
