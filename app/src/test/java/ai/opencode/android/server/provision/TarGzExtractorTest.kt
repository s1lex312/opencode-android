package ai.opencode.android.server.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

class TarGzExtractorTest {

    @Test
    fun `extracts files, directories and symlinks`() {
        val archive = tarGz(
            dir("rootfs/"),
            dir("rootfs/bin/"),
            file("rootfs/bin/sh", "#!/bin/sh\n"),
            symlink("rootfs/bin/sh-link", "sh"),
            file("proot", "fake proot binary", mode = 0b111_101_101),
        )

        val target = File(createTempDir(), "out")
        val stats = TarGzExtractor.extract(archive.inputStream(), target)

        assertEquals(5, stats.entries)
        assertTrue(File(target, "rootfs/bin/sh").isFile)
        assertEquals("#!/bin/sh\n", File(target, "rootfs/bin/sh").readText())
        assertTrue(File(target, "rootfs/bin/sh-link").isFile || java.nio.file.Files.isSymbolicLink(File(target, "rootfs/bin/sh-link").toPath))
        assertTrue(File(target, "proot").canExecute())
    }

    @Test
    fun `supports gnu long names`() {
        val longName = "rootfs/usr/" + "nested/".repeat(30) + "opencode"
        val archive = tarGz(
            longNameHeader(longName),
            file(longName, "binary"),
        )

        val target = File(createTempDir(), "long")
        TarGzExtractor.extract(archive.inputStream(), target)

        assertTrue(File(target, longName).isFile)
        assertEquals("binary", File(target, longName).readText())
    }

    @Test
    fun `rejects path traversal`() {
        val archive = tarGz(file("../escape.txt", "nope"))
        val target = File(createTempDir(), "safe")
        val failed = runCatching { TarGzExtractor.extract(archive.inputStream(), target) }.isFailure
        assertTrue("extraction should fail on ../", failed)
        assertFalse(File(target.parentFile, "escape.txt").exists())
    }

    @Test
    fun `handles empty and truncated archives`() {
        val target = File(createTempDir(), "empty")
        val stats = TarGzExtractor.extract(tarGz().inputStream(), target)
        assertEquals(0, stats.entries)
    }

    @Test
    fun `reports progress in bytes`() {
        val archive = tarGz(file("a.txt", "x".repeat(1000)), file("b.txt", "y".repeat(500)))
        val target = File(createTempDir(), "progress")
        var last = 0L
        TarGzExtractor.extract(archive.inputStream(), target) { last = it }
        assertEquals(1500L, last)
    }

    // ------------------------------------------------------------------ helpers

    private fun createTempDir(): File = File(System.getProperty("java.io.tmpdir"), "targz-" + System.nanoTime()).apply { mkdirs() }

    private data class Entry(val name: String, val type: Char, val data: ByteArray = ByteArray(0), val link: String = "", val mode: Int = 0b110_100_100)

    private fun file(name: String, content: String, mode: Int = 0b110_100_100) =
        Entry(name, '0', content.toByteArray(StandardCharsets.UTF_8), mode = mode)

    private fun dir(name: String) = Entry(name, '5')

    private fun symlink(name: String, target: String) = Entry(name, '2', link = target)

    private fun longNameHeader(name: String) = Entry("././@LongLink", 'L', name.toByteArray(StandardCharsets.UTF_8).copyOf(name.length + 1))

    private fun tarGz(vararg entries: Entry): File {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for (entry in entries) {
                gz.write(header(entry))
                if (entry.data.isNotEmpty()) {
                    gz.write(entry.data)
                    val padding = (512 - (entry.data.size % 512)) % 512
                    if (padding > 0) gz.write(ByteArray(padding))
                }
            }
            gz.write(ByteArray(1024))
        }
        val file = File(createTempDir(), "bundle.tar.gz")
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun header(entry: Entry): ByteArray {
        val block = ByteArray(512)
        putString(block, 0, 100, if (entry.type == 'L') "././@LongLink" else entry.name)
        putOctal(block, 100, 8, entry.mode.toLong())
        putOctal(block, 108, 8, 0)
        putOctal(block, 116, 8, 0)
        putOctal(block, 124, 12, entry.data.size.toLong())
        putOctal(block, 136, 12, 0)
        block[156] = entry.type.code.toByte()
        putString(block, 157, 100, entry.link)
        putString(block, 257, 6, "ustar\u0000")
        putString(block, 263, 2, "00")
        // checksum
        for (index in 148 until 156) block[index] = ' '.code.toByte()
        var sum = 0L
        for (byte in block) sum += (byte.toInt() and 0xFF)
        putOctal(block, 148, 8, sum)
        return block
    }

    private fun putString(block: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(bytes, 0, block, offset, minOf(bytes.size, length))
    }

    private fun putOctal(block: ByteArray, offset: Int, length: Int, value: Long) {
        val text = java.lang.Long.toOctalString(value).padStart(length - 1, '0') + "\u0000"
        putString(block, offset, length, text)
    }
}
