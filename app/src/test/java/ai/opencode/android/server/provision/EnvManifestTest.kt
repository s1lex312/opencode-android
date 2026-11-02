package ai.opencode.android.server.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvManifestTest {

    @Test
    fun `parses the shipped manifest`() {
        val manifest = EnvManifest.parse(
            """
            {
              "version": "2026.11-arm64",
              "abi": "arm64-v8a",
              "url": "https://example.com/opencode-env.tar.gz",
              "asset": "local-env.tar.gz",
              "sha256": "ABCDEF0123",
              "sizeBytes": 123456789,
              "notes": "arm64 bundle"
            }
            """.trimIndent(),
        )

        requireNotNull(manifest)
        assertEquals("2026.11-arm64", manifest.version)
        assertEquals("arm64-v8a", manifest.abi)
        assertTrue(manifest.isDownload)
        assertEquals(123456789L, manifest.sizeBytes)
        assertEquals("abcdef0123", manifest.requireHash())
        assertEquals("arm64 bundle", manifest.notes)
    }

    @Test
    fun `accepts snake_case size and unknown fields`() {
        val manifest = EnvManifest.parse("""{"version":"1","size_bytes":42,"futureField":{"a":1}}""")
        requireNotNull(manifest)
        assertEquals(42L, manifest.sizeBytes)
        assertEquals(EnvManifest.DEFAULT_ASSET, manifest.asset)
        assertFalse(manifest.isDownload)
        assertNull(manifest.requireHash())
    }

    @Test
    fun `blank sha is treated as missing`() {
        val manifest = EnvManifest.parse("""{"version":"1","sha256":"   "}""")
        requireNotNull(manifest)
        assertNull(manifest.requireHash())
    }

    @Test
    fun `returns null for garbage`() {
        assertNull(EnvManifest.parse("not json"))
        assertNull(EnvManifest.parseOrNull(null))
        assertNull(EnvManifest.parseOrNull("{]"))
    }

    @Test
    fun `encode round trips`() {
        val original = EnvManifest(version = "9", url = "https://h/b.tgz", sha256 = "aa", sizeBytes = 5)
        val restored = EnvManifest.parse(EnvManifest.encode(original))
        requireNotNull(restored)
        assertEquals(original.version, restored.version)
        assertEquals(original.url, restored.url)
        assertEquals(original.sha256, restored.sha256)
        assertEquals(original.sizeBytes, restored.sizeBytes)
    }
}
