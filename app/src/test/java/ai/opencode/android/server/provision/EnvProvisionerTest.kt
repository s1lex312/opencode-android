package ai.opencode.android.server.provision

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class EnvProvisionerTest {

    private class BytesSource(private val bytes: ByteArray, private val failOnOpen: Boolean = false) : ArtifactSource {
        override val label: String get() = "test-source"
        override val sizeBytes: Long get() = bytes.size.toLong()

        override fun open(): InputStream {
            if (failOnOpen) throw java.io.FileNotFoundException("the asset is not shipped")
            return bytes.inputStream()
        }
    }

    private fun bundle(): ByteArray = TestTar.build(
        TestTar.file("proot", "#!/bin/sh\necho proot", mode = 0b111_101_101),
        TestTar.dir("rootfs/"),
        TestTar.dir("rootfs/bin/"),
        TestTar.file("rootfs/bin/sh", "#!/bin/sh\n"),
        TestTar.file("rootfs/usr/local/bin/opencode", "opencode-binary"),
    )

    private fun provisioner(
        root: File,
        source: ArtifactSource?,
        bundled: EnvManifest? = null,
    ) = EnvProvisioner(
        paths = EnvPaths(root),
        bundledManifest = { bundled },
        sourceFactory = { source },
    )

    private fun manifestFor(bytes: ByteArray): EnvManifest = EnvManifest(
        version = "1.2.3",
        url = "https://example.invalid/local-env.tar.gz",
        sha256 = Digests.sha256(bytes),
        sizeBytes = bytes.size.toLong(),
    )

    @Test
    fun `installs a verified bundle and marks it ready`() = runBlocking {
        val root = File(tempDir(), "env")
        val bytes = bundle()
        val provisioner = provisioner(root, BytesSource(bytes), manifestFor(bytes))

        val result = provisioner.provision(manifestFor(bytes))

        assertTrue(result is ProvisionResult.Ok)
        assertEquals("1.2.3", (result as ProvisionResult.Ok).version)
        assertTrue(provisioner.isInstalled())
        assertEquals("1.2.3", provisioner.installedVersion())
        assertTrue(provisioner.paths().prootFile.isFile)
        assertTrue(provisioner.paths().prootFile.canExecute())
        assertTrue(File(provisioner.paths().rootfsDir, "bin/sh").isFile)
        assertEquals(ProvisionState.Phase.READY, provisioner.state.value.phase)
        assertEquals(1f, provisioner.state.value.progress, 0.001f)
    }

    @Test
    fun `rejects a bundle with a wrong checksum and leaves nothing behind`() = runBlocking {
        val root = File(tempDir(), "env")
        val bytes = bundle()
        val manifest = manifestFor(bytes).copy(sha256 = "0".repeat(64))
        val provisioner = provisioner(root, BytesSource(bytes), manifest)

        val result = provisioner.provision(manifest)

        assertTrue(result is ProvisionResult.Err)
        assertFalse(provisioner.isInstalled())
        assertEquals(ProvisionState.Phase.FAILED, provisioner.state.value.phase)
        assertNotNull(provisioner.state.value.error)
        assertFalse(File(root, "rootfs").exists())
        assertFalse(File(root, "proot").exists())
    }

    @Test
    fun `a stale hash never blocks an embedded (APK asset) bundle`() = runBlocking {
        // aapt2 repackages the asset, so the build-time hash cannot match the bytes in the APK.
        // The APK signature is the trust anchor here, not local-env.json.
        val root = File(tempDir(), "env")
        val bytes = bundle()
        val stale = EnvManifest(
            version = "1.1.6",
            asset = "local-env.tar",
            sha256 = Digests.sha256("some other bytes".toByteArray()),
            sizeBytes = bytes.size.toLong(),
        )
        val provisioner = provisioner(root, BytesSource(bytes), stale)

        val result = provisioner.provision(stale)

        assertTrue(result is ProvisionResult.Ok)
        assertTrue(provisioner.isInstalled())
        assertEquals("1.1.6", provisioner.installedVersion())
    }

    @Test
    fun `a wrong hash still blocks a user picked file`() = runBlocking {
        val root = File(tempDir(), "env")
        val bytes = bundle()
        val picked = File(root.parentFile, "picked.tar.gz").apply { writeBytes(bytes) }
        val manifest = EnvManifest(
            version = "imported",
            sha256 = Digests.sha256("different".toByteArray()),
            sizeBytes = bytes.size.toLong(),
        )
        val provisioner = provisioner(root, null)

        val result = provisioner.provision(manifest, FileArtifactSource(picked))

        assertTrue(result is ProvisionResult.Err)
        assertFalse(provisioner.isInstalled())
    }

    @Test
    fun `refuses downloads without a published hash`() = runBlocking {
        val root = File(tempDir(), "env")
        val bytes = bundle()
        val manifest = manifestFor(bytes).copy(sha256 = "")
        val provisioner = provisioner(root, BytesSource(bytes), manifest)

        val result = provisioner.provision(manifest)

        assertTrue(result is ProvisionResult.Err)
        assertFalse(provisioner.isInstalled())
    }

    @Test
    fun `surfaces a friendly message when the bundle is missing`() = runBlocking {
        val root = File(tempDir(), "env")
        val bytes = bundle()
        val provisioner = provisioner(root, BytesSource(bytes, failOnOpen = true), manifestFor(bytes))

        val result = provisioner.provision(manifestFor(bytes))

        assertTrue(result is ProvisionResult.Err)
        val message = (result as ProvisionResult.Err).message
        assertTrue(message.contains("not found", ignoreCase = true))
    }

    @Test
    fun `clear removes the environment`() = runBlocking {
        val root = File(tempDir(), "env")
        val bytes = bundle()
        val provisioner = provisioner(root, BytesSource(bytes), manifestFor(bytes))
        provisioner.provision(manifestFor(bytes))
        assertTrue(provisioner.isInstalled())

        val cleared = provisioner.clear()

        assertTrue(cleared)
        assertFalse(provisioner.isInstalled())
        assertNull(provisioner.installedVersion())
        assertEquals(ProvisionState.Phase.NOT_INSTALLED, provisioner.state.value.phase)
    }

    @Test
    fun `installed marker survives a new provisioner instance`() = runBlocking {
        val root = File(tempDir(), "env")
        val bytes = bundle()
        provisioner(root, BytesSource(bytes), manifestFor(bytes)).provision(manifestFor(bytes))

        val reopened = provisioner(root, null, null)

        assertTrue(reopened.isInstalled())
        assertEquals("1.2.3", reopened.installedVersion())
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "provisioner-" + System.nanoTime()).apply { mkdirs() }
}
