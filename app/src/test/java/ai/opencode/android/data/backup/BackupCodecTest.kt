package ai.opencode.android.data.backup

import ai.opencode.android.data.remote.OpencodeJson
import ai.opencode.android.update.AppRelease
import ai.opencode.android.update.AppUpdater
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BackupCodecTest {

    private val prefs: Map<String, Any> = mapOf(
        "server_url" to "http://10.0.0.2:4096",
        "notifications" to false,
        "local_server_port" to 4100,
        "opencode_last_update_check" to 1_730_000_000_000L,
        "scale" to 1.25f,
        "ratio" to 0.5,
        "tags" to setOf("b", "a"),
    )

    @Test
    fun `preferences keep their types through a round trip`() {
        val decoded = BackupCodec.decodePrefs(BackupCodec.encodePrefs(prefs))
        assertEquals(prefs, decoded)
        assertTrue(decoded["local_server_port"] is Int)
        assertTrue(decoded["opencode_last_update_check"] is Long)
        assertTrue(decoded["scale"] is Float)
    }

    @Test
    fun `snapshot survives encode and decode, foreign files are rejected`() {
        val snapshot = BackupSnapshot(
            appVersion = "1.6.0",
            createdAt = 42,
            settings = BackupCodec.encodePrefs(prefs),
            files = mapOf("workbench.json" to "{}"),
            server = mapOf("root/.config/opencode/opencode.json" to "{\"mcp\":{}}"),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(snapshot))
        assertEquals(snapshot, decoded)
        assertFalse(decoded.needsPassword)

        assertRejected { BackupCodec.decode("{\"format\":\"something-else\"}") }
        assertRejected { BackupCodec.decode("not json") }
        assertRejected { BackupCodec.decode(BackupCodec.encode(snapshot.copy(version = BackupCodec.VERSION + 1))) }
    }

    @Test
    fun `secrets are sealed with the password and only open with it`() {
        val secrets = BackupSecrets(
            store = mapOf("server_password" to "hunter2", "profile:abc" to "token"),
            server = mapOf("root/.local/share/opencode/auth.json" to "{\"openai\":{\"type\":\"api\",\"key\":\"sk-1\"}}"),
        )
        val blob = BackupCodec.seal(secrets, "correct horse")
        val encoded = BackupCodec.encode(BackupSnapshot(encryptedSecrets = blob))
        assertFalse(encoded.contains("hunter2"))
        assertFalse(encoded.contains("sk-1"))

        val snapshot = BackupCodec.decode(encoded)
        assertTrue(snapshot.needsPassword)
        assertEquals(secrets, BackupCodec.open(snapshot.encryptedSecrets!!, "correct horse"))
        assertThrows<WrongBackupPassword> { BackupCodec.open(blob, "wrong") }
        // Fresh salt and IV every time.
        assertNotEquals(blob.data, BackupCodec.seal(secrets, "correct horse").data)
    }

    @Test
    fun `fingerprint ignores time and changes with content or password`() {
        val base = BackupSnapshot(settings = BackupCodec.encodePrefs(prefs))
        val secrets = BackupSecrets(store = mapOf("k" to "v"))
        val a = BackupCodec.fingerprint(base.copy(createdAt = 1), secrets, "stamp1")
        assertEquals(a, BackupCodec.fingerprint(base.copy(createdAt = 2), secrets, "stamp1"))
        assertNotEquals(a, BackupCodec.fingerprint(base, secrets.copy(store = mapOf("k" to "w")), "stamp1"))
        assertNotEquals(a, BackupCodec.fingerprint(base, secrets, "stamp2"))
        assertNotEquals(a, BackupCodec.fingerprint(base.copy(files = mapOf("x" to "y")), secrets, "stamp1"))
    }

    @Test
    fun `release parsing picks this flavour's APK`() {
        val json = OpencodeJson.parseToJsonElement(
            """
            {"tag_name":"v1.6.0","draft":false,"prerelease":false,"body":"notes",
             "assets":[
               {"name":"opencode-android-full-v1.6.0.apk","browser_download_url":"https://x/full.apk","size":130000000,"digest":"sha256:aa"},
               {"name":"opencode-android-lite-v1.6.0.apk","browser_download_url":"https://x/lite.apk","size":22000000,"digest":"sha256:bb"},
               {"name":"SHA256SUMS.txt","browser_download_url":"https://x/sums","size":10}
             ]}
            """.trimIndent(),
        ) as JsonObject
        val lite = AppUpdater.parseRelease(json, "lite")
        assertEquals(AppRelease("1.6.0", "https://x/lite.apk", "bb", 22_000_000, "notes"), lite)
        assertEquals("https://x/full.apk", AppUpdater.parseRelease(json, "full").url)
        assertThrows<IOException> { AppUpdater.parseRelease(json, "other") }

        val pre = OpencodeJson.parseToJsonElement("""{"tag_name":"v2.0.0","prerelease":true,"assets":[]}""") as JsonObject
        assertThrows<IOException> { AppUpdater.parseRelease(pre, "lite") }
        val noDigest = OpencodeJson.parseToJsonElement(
            """{"tag_name":"v1.6.1","assets":[{"name":"opencode-android-lite-v1.6.1.apk","browser_download_url":"u"}]}""",
        ) as JsonObject
        assertNull(AppUpdater.parseRelease(noDigest, "lite").sha256)
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            assertTrue("expected ${T::class.simpleName}, got $t", t is T)
            return
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    private fun assertRejected(block: () -> Unit) = assertThrows<IllegalArgumentException>(block)
}
