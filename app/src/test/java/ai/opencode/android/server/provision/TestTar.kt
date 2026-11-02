package ai.opencode.android.server.provision

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/** Builds small `.tar.gz` archives for tests (ustar headers, regular files, dirs, symlinks). */
object TestTar {

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

    fun build(vararg entries: Entry): ByteArray {
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
        return out.toByteArray()
    }

    fun buildFile(directory: File, name: String, vararg entries: Entry): File {
        val file = File(directory, name)
        file.writeBytes(build(*entries))
        return file
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
