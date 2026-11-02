package ai.opencode.android.settings

import ai.opencode.android.server.update.OpencodeUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {

    @Test
    fun `system prompts round-trip through json`() {
        val prompts = listOf(
            SystemPromptProfile("1", "Кратко", "Отвечай кратко.\n\"quotes\" and \\ slashes"),
            SystemPromptProfile("2", "", ""),
        )
        assertEquals(prompts, ProfileCodec.decodePrompts(ProfileCodec.encodePrompts(prompts)))
        assertEquals(emptyList<SystemPromptProfile>(), ProfileCodec.decodePrompts("not json"))
    }

    @Test
    fun `server profiles round-trip and match settings`() {
        val profile = ServerProfile("a", "VPS", ConnectionMode.REMOTE, "https://vps.example:4096/", AuthMode.BASIC, "me", false)
        assertEquals(listOf(profile), ProfileCodec.decodeServers(ProfileCodec.encodeServers(listOf(profile))))

        val settings = AppSettings(
            connectionMode = ConnectionMode.REMOTE,
            serverUrl = "https://vps.example:4096",
            authMode = AuthMode.BASIC,
            username = "me",
        )
        assertTrue(profile.matches(settings))
        assertFalse(profile.matches(settings.copy(connectionMode = ConnectionMode.LOCAL)))
        assertTrue(ServerProfile("b", "Phone", ConnectionMode.LOCAL).matches(AppSettings()))
    }

    @Test
    fun `version comparison`() {
        assertTrue(OpencodeUpdater.compareVersions("1.19.6", "1.19.5") > 0)
        assertTrue(OpencodeUpdater.compareVersions("v1.20.0", "1.19.12") > 0)
        assertEquals(0, OpencodeUpdater.compareVersions("1.2", "1.2.0"))
        assertTrue(OpencodeUpdater.compareVersions("2.0.0-beta.1", "2.0.0") < 0)
        assertTrue(OpencodeUpdater.compareVersions("1.9.0", "1.10.0") < 0)
    }
}
