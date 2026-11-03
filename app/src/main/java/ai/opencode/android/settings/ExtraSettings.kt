package ai.opencode.android.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How the proxy reaches its upstream. */
@Serializable
enum class ProxyType { HTTP, SOCKS5 }

/** Which hosts go through the proxy. */
@Serializable
enum class ProxyScope {
    /** Only the hosts in [ProxySettings.hosts] (model providers by default); the rest goes direct. */
    LISTED,

    /** Everything except [ProxySettings.bypass] and local addresses. */
    ALL,
}

/**
 * Upstream proxy for model providers that are not available in the user's region.
 *
 * The app runs a small HTTP proxy on 127.0.0.1 that forwards to this upstream, so the in-app
 * server only ever sees `HTTPS_PROXY=http://127.0.0.1:<port>` (no credentials in its environment)
 * and SOCKS5 works for Bun, Node, pip, git and apt alike. The password is kept in the
 * [ai.opencode.android.security.SecretStore] under [PASSWORD_KEY].
 */
@Serializable
data class ProxySettings(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val scope: ProxyScope = ProxyScope.LISTED,
    val hosts: List<String> = DEFAULT_HOSTS,
    val bypass: List<String> = emptyList(),
    /** Environment of the in-app server: models, npm, pip, apt, git. */
    val applyToServer: Boolean = true,
    /** The app's own traffic: a remote opencode server, update checks, downloads. */
    val applyToApp: Boolean = false,
) {
    val configured: Boolean get() = host.isNotBlank() && port in 1..65535

    val active: Boolean get() = enabled && configured

    companion object {
        const val PASSWORD_KEY = "proxy_password"

        /** API hosts of the providers opencode ships, plus the auth hosts some of them log in through. */
        val DEFAULT_HOSTS = listOf(
            "api.openai.com",
            "chatgpt.com",
            "auth.openai.com",
            "api.anthropic.com",
            "console.anthropic.com",
            "claude.ai",
            "generativelanguage.googleapis.com",
            "aiplatform.googleapis.com",
            "oauth2.googleapis.com",
            "api.x.ai",
            "api.mistral.ai",
            "api.groq.com",
            "api.cohere.com",
            "api.perplexity.ai",
            "api.together.xyz",
            "api.fireworks.ai",
            "api.deepinfra.com",
            "api.cerebras.ai",
            "openrouter.ai",
            "api.githubcopilot.com",
            "*.githubcopilot.com",
            "github.com",
            "api.github.com",
            "*.openai.azure.com",
            "*.cognitiveservices.azure.com",
            "bedrock-runtime.*.amazonaws.com",
            "api.deepseek.com",
            "opencode.ai",
            "models.dev",
        )
    }
}

/** A font the user can pick for the UI or the chat. */
@Serializable
enum class FontChoice { SYSTEM, SANS, SERIF, MONO }

/**
 * Everything about the look beyond the palette: per-role colour overrides for light and dark,
 * text sizes, fonts, corner radii, spacing and which chat elements are shown.
 *
 * Colours are ARGB longs keyed by [ai.opencode.android.ui.theme.ColorRole.key]; a missing key keeps
 * the palette's colour, so a theme only stores what the user changed.
 */
@Serializable
data class UiCustomization(
    val light: Map<String, Long> = emptyMap(),
    val dark: Map<String, Long> = emptyMap(),
    /** Every text in the app (on top of the system font size). */
    val uiFontScale: Float = 1f,
    /** Chat prose, on top of [uiFontScale]. */
    val chatFontScale: Float = 1f,
    /** Code blocks, tool output, diffs, terminal. */
    val codeFontScale: Float = 1f,
    val lineHeightScale: Float = 1f,
    val uiFont: FontChoice = FontChoice.SYSTEM,
    val chatFont: FontChoice = FontChoice.SYSTEM,
    /** Multiplies every corner radius; 0 gives square corners. */
    val cornerScale: Float = 1f,
    /** Multiplies the gaps between chat messages and inside settings rows. */
    val spacingScale: Float = 1f,
    /** Content column width on tablets and in landscape. */
    val contentMaxWidthDp: Int = 840,
    val bubbleRadiusDp: Int = 22,
    val bubbleMaxWidthDp: Int = 640,
    val showStatusLine: Boolean = true,
    val compactTopBars: Boolean = false,
    val animations: Boolean = true,
) {
    fun colors(dark: Boolean): Map<String, Long> = if (dark) this.dark else light

    fun withColor(dark: Boolean, key: String, argb: Long?): UiCustomization {
        val map = colors(dark).let { if (argb == null) it - key else it + (key to argb) }
        return if (dark) copy(dark = map) else copy(light = map)
    }

    fun normalized(): UiCustomization = copy(
        uiFontScale = uiFontScale.coerceIn(0.7f, 1.6f),
        chatFontScale = chatFontScale.coerceIn(0.6f, 2f),
        codeFontScale = codeFontScale.coerceIn(0.6f, 2f),
        lineHeightScale = lineHeightScale.coerceIn(0.8f, 1.8f),
        cornerScale = cornerScale.coerceIn(0f, 2.5f),
        spacingScale = spacingScale.coerceIn(0.3f, 2.5f),
        contentMaxWidthDp = contentMaxWidthDp.coerceIn(360, 2400),
        bubbleRadiusDp = bubbleRadiusDp.coerceIn(0, 40),
        bubbleMaxWidthDp = bubbleMaxWidthDp.coerceIn(200, 2400),
    )
}

/** A named look the user saved: palette, mode and every customisation. */
@Serializable
data class SavedTheme(
    val id: String,
    val name: String,
    val palette: ThemePalette = ThemePalette.CLAUDE,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pureBlack: Boolean = false,
    val custom: UiCustomization = UiCustomization(),
)

/** JSON for the settings above; lenient so older and newer app versions read each other's values. */
object ExtraSettingsCodec {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
        prettyPrint = false
    }

    private val pretty = Json(json) { prettyPrint = true }

    fun decodeProxy(raw: String?): ProxySettings =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(ProxySettings.serializer(), it) }.getOrNull() }
            ?: ProxySettings()

    fun encodeProxy(value: ProxySettings): String = json.encodeToString(ProxySettings.serializer(), value)

    fun decodeUi(raw: String?): UiCustomization =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(UiCustomization.serializer(), it) }.getOrNull() }
            ?.normalized() ?: UiCustomization()

    fun encodeUi(value: UiCustomization): String = json.encodeToString(UiCustomization.serializer(), value)

    fun decodeThemes(raw: String?): List<SavedTheme> =
        raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SavedTheme.serializer()), it) }.getOrNull()
        } ?: emptyList()

    fun encodeThemes(value: List<SavedTheme>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SavedTheme.serializer()), value)

    /** Shareable theme file: one [SavedTheme], pretty printed. */
    fun exportTheme(theme: SavedTheme): String = pretty.encodeToString(SavedTheme.serializer(), theme)

    fun importTheme(raw: String): SavedTheme =
        json.decodeFromString(SavedTheme.serializer(), raw.trim()).let { it.copy(custom = it.custom.normalized()) }
}
