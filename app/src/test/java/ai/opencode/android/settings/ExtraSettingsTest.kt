package ai.opencode.android.settings

import ai.opencode.android.data.diagnostics.Redact
import ai.opencode.android.data.repo.neverReachedServer
import ai.opencode.android.data.remote.ApiException
import ai.opencode.android.ui.theme.ColorRoles
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

class ExtraSettingsTest {

    @Test
    fun `proxy and ui settings survive a round trip and garbage falls back to defaults`() {
        val proxy = ProxySettings(enabled = true, type = ProxyType.SOCKS5, host = "10.8.0.1", port = 1080, username = "me", scope = ProxyScope.ALL, bypass = listOf("example.com"))
        assertEquals(proxy, ExtraSettingsCodec.decodeProxy(ExtraSettingsCodec.encodeProxy(proxy)))
        assertEquals(ProxySettings(), ExtraSettingsCodec.decodeProxy("{broken"))
        assertEquals(ProxySettings(), ExtraSettingsCodec.decodeProxy(null))

        val ui = UiCustomization(dark = mapOf("primary" to 0xFF112233L), uiFontScale = 1.2f, chatFont = FontChoice.SERIF, showStatusLine = false)
        assertEquals(ui, ExtraSettingsCodec.decodeUi(ExtraSettingsCodec.encodeUi(ui)))
        // Values from a hand-edited file are clamped instead of breaking the layout.
        val clamped = ExtraSettingsCodec.decodeUi("""{"uiFontScale":9,"cornerScale":-1,"bubbleRadiusDp":500,"unknownField":1}""")
        assertEquals(1.6f, clamped.uiFontScale)
        assertEquals(0f, clamped.cornerScale)
        assertEquals(40, clamped.bubbleRadiusDp)
    }

    @Test
    fun `themes export and import without sharing ids`() {
        val theme = SavedTheme("id-1", "Night", ThemePalette.VIOLET, ThemeMode.DARK, pureBlack = true, custom = UiCustomization(light = mapOf("background" to 0xFFFFFFFFL)))
        val imported = ExtraSettingsCodec.importTheme(ExtraSettingsCodec.exportTheme(theme))
        assertEquals(theme, imported)
        assertTrue(runCatching { ExtraSettingsCodec.importTheme("[]") }.isFailure)
    }

    @Test
    fun `colour overrides are per mode and removable`() {
        val custom = UiCustomization().withColor(dark = true, key = "primary", argb = 0xFF00FF00L)
        assertEquals(mapOf("primary" to 0xFF00FF00L), custom.colors(true))
        assertTrue(custom.colors(false).isEmpty())
        assertTrue(custom.withColor(true, "primary", null).dark.isEmpty())
    }

    @Test
    fun `hex colours parse in every common form`() {
        assertEquals(0xFF112233L, ColorRoles.parseHex("#112233"))
        assertEquals(0x80112233L, ColorRoles.parseHex("80112233"))
        assertEquals(0xFFAABBCCL, ColorRoles.parseHex("#abc"))
        assertNull(ColorRoles.parseHex("#12345"))
        assertNull(ColorRoles.parseHex("zzzzzz"))
        assertEquals("#112233", ColorRoles.hex(0xFF112233L))
        assertEquals("#80112233", ColorRoles.hex(0x80112233L))
    }

    @Test
    fun `schema validates typed input and every key is unique`() {
        val keys = SettingsSchema.all.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val port = SettingsSchema.spec("local_server_port", 4096)
        assertEquals(4100, port.validate("4100").getOrNull())
        assertTrue(port.validate("80").isFailure)
        assertTrue(port.validate("abc").isFailure)
        val mode = SettingsSchema.spec("theme_mode", "SYSTEM")
        assertTrue(mode.validate("DARK").isSuccess)
        assertTrue(mode.validate("PURPLE").isFailure)
        assertTrue(SettingsSchema.spec("proxy", "").validate("{\"enabled\":true}").isSuccess)
        assertTrue(SettingsSchema.spec("proxy", "").validate("{oops").isFailure)
        val unknown = SettingsSchema.spec("some_plugin_flag", true)
        assertEquals(SettingType.BOOL, unknown.type)
        assertEquals(SettingGroup.OTHER, unknown.group)
    }

    @Test
    fun `redaction removes keys from logs and json`() {
        val log = "auth ok key=sk-abcdefghijklmnop Authorization: Bearer abc.def.ghijklmnop url=https://user:pw@host/x token: \"ghp_abcdefghijklmnopqrstuvwxyz12\""
        val clean = Redact.text(log)
        listOf("sk-abcdefghijklmnop", "abc.def.ghijklmnop", "user:pw", "ghp_abcdefghijklmnopqrstuvwxyz12").forEach { assertFalse(clean, clean.contains(it)) }
        val json = Redact.json(Json.parseToJsonElement("""{"provider":{"openai":{"options":{"apiKey":"abc123","baseURL":"https://x"}}},"mcp":{"g":{"headers":{"Authorization":"Bearer zzz"}}}}"""))
        assertFalse(json.contains("abc123"))
        assertFalse(json.contains("zzz"))
        assertTrue(json.contains("https://x"))
    }

    @Test
    fun `only failures before the server saw the request are retried offline`() {
        assertTrue(neverReachedServer(ApiException(null, "x", cause = ConnectException("refused"))))
        assertTrue(neverReachedServer(SocketTimeoutException("connect timed out")))
        assertFalse(neverReachedServer(SocketTimeoutException("timeout")))
        assertFalse(neverReachedServer(ApiException(500, "boom")))
    }
}
