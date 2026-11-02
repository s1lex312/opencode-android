package ai.opencode.android.server.provision

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Minimal, dependency-free reader for `.tar.gz` archives.
 *
 * The provisioning bundle is produced by GNU tar, so the reader supports the formats that
 * actually appear in a Debian/Ubuntu rootfs:
 *
 *  * ustar names, including the 155-byte `prefix` field;
 *  * GNU long name / long link ('L' / 'K');
 *  * pax extended headers ('x') carrying `path=` and `linkpath=`;
 *  * regular files, directories, symlinks and hard links;
 *  * device nodes and FIFOs are skipped (they cannot be created without root anyway).
 *
 * Safety: every entry is normalised and checked to stay inside the target directory, so a
 * malicious or corrupted archive cannot write outside the app's sandbox (zip-slip).
 */
object TarGzExtractor {

    private const val BLOCK = 512
    private const val MAX_PAX_BYTES = 1 shl 20

    data class Stats(val entries: Int, val bytes: Long)

    fun extract(
        stream: InputStream,
        target: File,
        onProgress: (Long) -> Unit = {},
    ): Stats {
        target.mkdirs()
        if (!target.isDirectory) throw IOException("Cannot create extraction directory: ${target.path}")

        var entries = 0
        var written = 0L
        var pendingName: String? = null
        var pendingLink: String? = null

        GZIPInputStream(stream, 64 * 1024).use { gz ->
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readFully(gz, header, BLOCK)) break
                if (header.isZeroBlock()) break

                val type = header[156].toInt().toChar()
                val size = parseNumeric(header, 124, 12)
                var name = cString(header, 0, 100)
                val prefix = cString(header, 345, 155)
                if (prefix.isNotEmpty() && name.isNotEmpty()) name = "$prefix/$name"
                var linkName = cString(header, 157, 100)
                val mode = parseNumeric(header, 100, 8)

                when (type) {
                    'L' -> {
                        val payload = readPayload(gz, size, MAX_PAX_BYTES)
                        pendingName = payload.toString(StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
                        skipPadding(gz, size)
                        continue
                    }

                    'K' -> {
                        val payload = readPayload(gz, size, MAX_PAX_BYTES)
                        pendingLink = payload.toString(StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
                        skipPadding(gz, size)
                        continue
                    }

                    'x' -> {
                        val payload = readPayload(gz, size, MAX_PAX_BYTES)
                        parsePax(payload).let { pax ->
                            pax["path"]?.let { pendingName = it }
                            pax["linkpath"]?.let { pendingLink = it }
                        }
                        skipPadding(gz, size)
                        continue
                    }

                    'g' -> {
                        readPayload(gz, size, MAX_PAX_BYTES)
                        skipPadding(gz, size)
                        continue
                    }
                }

                pendingName?.let { name = it }
                pendingLink?.let { linkName = it }
                pendingName = null
                pendingLink = null

                if (name.isEmpty()) {
                    skipPayload(gz, size)
                    continue
                }

                val destination = safeTarget(target, name)
                when (type) {
                    '5' -> {
                        destination.mkdirs()
                        applyMode(destination, mode, isDirectory = true)
                    }

                    '2' -> {
                        destination.parentFile?.mkdirs()
                        destination.delete()
                        runCatching { Files.createSymbolicLink(destination.toPath(), File(linkName).toPath()) }
                            .onFailure { throw IOException("Cannot create symlink $name -> $linkName", it) }
                    }

                    '1' -> {
                        destination.parentFile?.mkdirs()
                        val linkTarget = safeTarget(target, linkName)
                        destination.delete()
                        val linked = runCatching { Files.createLink(destination.toPath(), linkTarget.toPath()) }.isSuccess
                        if (!linked && linkTarget.isFile) linkTarget.copyTo(destination, overwrite = true)
                    }

                    '0', '\u0000', '7' -> {
                        destination.parentFile?.mkdirs()
                        destination.outputStream().buffered(64 * 1024).use { out ->
                            written += copyExact(gz, out, size)
                        }
                        applyMode(destination, mode, isDirectory = false)
                        onProgress(written)
                    }

                    else -> {
                        // '3' char device, '4' block device, '6' fifo: not creatable without root.
                    }
                }

                skipPadding(gz, size)
                entries += 1
            }
        }
        return Stats(entries, written)
    }

    // ------------------------------------------------------------------ helpers

    private fun safeTarget(root: File, name: String): File {
        val cleaned = name.removePrefix("./").trimStart('/')
        val rootCanonical = root.canonicalFile
        val candidate = File(rootCanonical, cleaned).canonicalFile
        val rootPrefix = rootCanonical.path + File.separator
        if (candidate != rootCanonical && !candidate.path.startsWith(rootPrefix)) {
            throw IOException("Unsafe path in archive: $name")
        }
        return candidate
    }

    private fun applyMode(file: File, mode: Long, isDirectory: Boolean) {
        if (isDirectory) {
            file.setReadable(true, false)
            file.setExecutable(true, false)
        } else {
            file.setReadable(true, false)
            val executable = mode and 0b001_001_001 != 0L
            file.setExecutable(executable, false)
        }
    }

    private fun ByteArray.isZeroBlock(): Boolean {
        for (byte in this) if (byte != 0.toByte()) return false
        return true
    }

    private fun cString(buffer: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = minOf(offset + length, buffer.size)
        while (end < limit && buffer[end] != 0.toByte()) end += 1
        return String(buffer, offset, end - offset, StandardCharsets.UTF_8)
    }

    /** Octal (or base-256) numeric field; returns 0 for anything unparsable. */
    private fun parseNumeric(buffer: ByteArray, offset: Int, length: Int): Long {
        if (length <= 0 || offset + length > buffer.size) return 0
        if (buffer[offset].toInt() and 0x80 != 0) {
            var value = 0L
            for (index in 1 until length) {
                value = (value shl 8) or (buffer[offset + index].toLong() and 0xFF)
            }
            return value
        }
        val text = cString(buffer, offset, length).trim().trim('\u0000')
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: 0
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var read = 0
        while (read < length) {
            val chunk = input.read(buffer, read, length - read)
            if (chunk < 0) return read > 0 && read == length
            read += chunk
        }
        return true
    }

    private fun readPayload(input: InputStream, size: Long, limit: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        val length = minOf(size, limit.toLong()).toInt()
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = input.read(buffer, read, length - read)
            if (chunk < 0) break
            read += chunk
        }
        return if (read == length) buffer else buffer.copyOf(read)
    }

    private fun copyExact(input: InputStream, output: OutputStream, size: Long): Long {
        if (size <= 0) return 0
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        var total = 0L
        while (remaining > 0) {
            val chunk = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (chunk < 0) throw IOException("Unexpected end of archive")
            output.write(buffer, 0, chunk)
            remaining -= chunk
            total += chunk
        }
        return total
    }

    private fun skipPayload(input: InputStream, size: Long) {
        if (size <= 0) return
        var remaining = size
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val chunk = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (chunk < 0) return
            remaining -= chunk
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (BLOCK - (size % BLOCK)) % BLOCK
        if (padding > 0) skipPayload(input, padding)
    }

    private fun parsePax(payload: ByteArray): Map<String, String> {
        val text = payload.toString(StandardCharsets.UTF_8)
        val result = mutableMapOf<String, String>()
        var cursor = 0
        while (cursor < text.length) {
            val space = text.indexOf(' ', cursor)
            if (space < 0) break
            val length = text.substring(cursor, space).toIntOrNull() ?: break
            if (length <= 0 || cursor + length > text.length) break
            val record = text.substring(space + 1, cursor + length).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) result[record.substring(0, equals)] = record.substring(equals + 1)
            cursor += length
        }
        return result
    }
}
