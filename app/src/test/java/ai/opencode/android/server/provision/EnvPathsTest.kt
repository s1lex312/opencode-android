package ai.opencode.android.server.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EnvPathsTest {

    private fun tempDir(): File = Files.createTempDirectory("envpaths").toFile()

    @Test
    fun `prefers the proot packaged in the APK`() {
        val root = File(tempDir(), "opencode-env")
        val nativeLibDir = File(tempDir(), "lib/arm64")
        nativeLibDir.mkdirs()
        File(nativeLibDir, "libproot.so").writeText("elf")
        File(root, "proot").mkdirs()
        File(root, "proot/proot").writeText("elf")

        val paths = EnvPaths(root, nativeLibDir)

        // Android 10+ cannot exec from app storage, so the packaged copy must win.
        assertEquals(File(nativeLibDir, "libproot.so"), paths.prootBinary)
    }

    @Test
    fun `falls back to the extracted copy on older devices`() {
        val root = File(tempDir(), "opencode-env")
        File(root, "proot").mkdirs()
        File(root, "proot/proot").writeText("elf")
        val nativeLibDir = File(tempDir(), "lib/arm64").apply { mkdirs() }

        val paths = EnvPaths(root, nativeLibDir)

        assertEquals(File(root, "proot/proot"), paths.prootBinary)
    }

    @Test
    fun `reports no proot when neither copy exists`() {
        val paths = EnvPaths(File(tempDir(), "empty"), File(tempDir(), "lib"))

        assertNull(paths.prootBinary)
        assertNull(paths.nativeProot)
        assertNull(paths.nativeProotLoader)
        assertNull(paths.nativeProotLoader32)
    }

    @Test
    fun `finds both packaged loaders next to libproot`() {
        val nativeLibDir = File(tempDir(), "lib/arm64").apply { mkdirs() }
        File(nativeLibDir, "libproot.so").writeText("elf")
        File(nativeLibDir, "libproot_loader.so").writeText("elf")
        File(nativeLibDir, "libproot_loader32.so").writeText("elf")

        val paths = EnvPaths(File(tempDir(), "opencode-env"), nativeLibDir)

        assertEquals(File(nativeLibDir, "libproot_loader.so"), paths.nativeProotLoader)
        assertEquals(File(nativeLibDir, "libproot_loader32.so"), paths.nativeProotLoader32)
    }

    @Test
    fun `never claims the native library directory is writable`() {
        val root = File(tempDir(), "opencode-env")
        val nativeLibDir = File(tempDir(), "lib/arm64").apply { mkdirs() }
        val paths = EnvPaths(root, nativeLibDir)

        assertTrue(paths.isNativeLibraryPath(nativeLibDir))
        assertTrue(paths.isNativeLibraryPath(File(nativeLibDir, "libproot.so")))
        assertFalse(paths.isNativeLibraryPath(paths.rootfsDir))
        assertFalse(paths.isNativeLibraryPath(paths.prootFile))
        assertFalse(EnvPaths(root).isNativeLibraryPath(paths.rootfsDir))
    }

    @Test
    fun `native library dir comes first on the loader path`() {
        val root = File(tempDir(), "opencode-env")
        val nativeLibDir = File(tempDir(), "lib/arm64").apply { mkdirs() }
        File(nativeLibDir, "libproot.so").writeText("elf")
        File(root, "proot/lib").mkdirs()

        val dirs = EnvPaths(root, nativeLibDir).prootLibraryDirs()

        assertEquals(listOf(nativeLibDir, File(root, "proot/lib")), dirs)
    }
}
