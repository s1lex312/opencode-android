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
enum class ThemePalette { CLAUDE, OPENCODE, DYNAMIC, EMERALD, AMBER, VIOLET, ROSE }

/** Transcript layout: terminal-like Claude Code lines or chat bubbles. */
enum class ChatStyle { CLAUDE_CODE, BUBBLES }

/** Everything about how the chat looks and behaves on this device (not sent to opencode). */
data class ChatSettings(
    val style: ChatStyle = ChatStyle.CLAUDE_CODE,
    /** Monospace prose, like a terminal. Code and tool lines are always monospace. */
    val monospace: Boolean = false,
    val expandReasoning: Boolean = false,
    val expandTools: Boolean = false,
    /** Output lines shown under a collapsed tool call (`⎿`). 0 hides the preview. */
    val toolPreviewLines: Int = 3,
    val sendOnEnter: Boolean = false,
    val showTimestamps: Boolean = true,
    val showCost: Boolean = true,
    val contextWarnPercent: Int = 70,
    val contextDangerPercent: Int = 90,
    /** Compact automatically after a turn once the context is this full; 0 = never. */
    val autoCompactPercent: Int = 0,
    val haptics: Boolean = true,
    /** "Pondering…", "Brewing…" instead of a plain "Working…". */
    val playfulSpinner: Boolean = true,
)

/** What the agent may touch on the phone itself (Local mode). */
data class PhoneAccessSettings(
    /** Bind shared storage (`/sdcard`) into the Linux environment. Needs "All files access". */
    val storage: Boolean = false,
    /** Built-in MCP server with phone tools (clipboard, notifications, apps, battery, torch...). */
    val androidTools: Boolean = true,
)

/** Relative size of chat text. */
enum class TextScale(val factor: Float) { SMALL(0.9f), DEFAULT(1f), LARGE(1.15f) }

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.CLAUDE,
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
    /** Check GitHub releases for a newer APK and download it in the background. */
    val appAutoUpdate: Boolean = true,
    /** Install a downloaded update without asking (silent on Android 12+ once the app installed itself). */
    val appAutoInstall: Boolean = true,
    val chat: ChatSettings = ChatSettings(),
    val phone: PhoneAccessSettings = PhoneAccessSettings(),
    /** User context window per model (`provider/model` → tokens), overrides the catalogue. */
    val modelContextLimits: Map<String, Long> = emptyMap(),
    /** User context window per chat (session id → tokens), overrides the model's. */
    val sessionContextLimits: Map<String, Long> = emptyMap(),
) {
    /** Context window to use for [sessionId] running [modelKey]; `null` falls back to the catalogue. */
    fun contextLimitOverride(sessionId: String?, modelKey: String?): Long? =
        sessionId?.let { sessionContextLimits[it] } ?: modelKey?.let { modelContextLimits[it] }

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
