package ai.opencode.android.server.provision

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/** Builds small `.tar.gz` archives for tests (ustar headers, regular files, dirs, symlinks). */
object TestTar {

    private const val BLOCK = 512

    data class Entry(
        val name: String,
        val type: Char = '0',
        val data: ByteArray = ByteArray(0),
        val link: String = "",
        val mode: Int = 0b110_100_100,
    )

    fun file(name: String, content: String, mode: Int = 0b110_100_100): Entry =
        Entry(name, '0', content.toByteArray(StandardCharsets.UTF_8), mode = mode)

    fun dir(name: String): Entry = Entry(name, '5')

    fun symlink(name: String, target: String): Entry = Entry(name, '2', link = target)

    /** Same tar, but without the gzip wrapper — exactly what aapt2 leaves inside an APK. */
    fun buildRaw(vararg entries: Entry): ByteArray = tar(entries.toList())

    fun build(vararg entries: Entry): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz -> gz.write(tar(entries.toList())) }
        return out.toByteArray()
    }

    fun buildFile(directory: File, name: String, vararg entries: Entry): File {
        val file = File(directory, name)
        file.writeBytes(build(*entries))
        return file
    }

    /** Plain (uncompressed) tar: ustar headers, file payloads, two zero blocks at the end. */
    fun tar(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            out.write(header(entry))
            if (entry.data.isNotEmpty()) {
                out.write(entry.data)
                val padding = (BLOCK - (entry.data.size % BLOCK)) % BLOCK
                if (padding > 0) out.write(ByteArray(padding))
            }
        }
        out.write(ByteArray(BLOCK * 2))
        return out.toByteArray()
    }

    private fun header(entry: Entry): ByteArray {
        val block = ByteArray(512)
        putString(block, 0, 100, entry.name)
        putOctal(block, 100, 8, entry.mode.toLong())
        putOctal(block, 108, 8, 0)
        putOctal(block, 116, 8, 0)
        putOctal(block, 124, 12, entry.data.size.toLong())
        putOctal(block, 136, 12, 0)
        block[156] = entry.type.code.toByte()
        putString(block, 157, 100, entry.link)
        putString(block, 257, 6, "ustar\u0000")
        putString(block, 263, 2, "00")
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
