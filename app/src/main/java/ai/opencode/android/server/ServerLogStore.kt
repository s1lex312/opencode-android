package ai.opencode.android.server

import java.io.File

/**
 * Append-only log file for the local server with simple size-based rotation.
 *
 * A file (not just the in-memory ring buffer) is required so the log survives process death and
 * can be attached to a bug report. Rotation keeps at most ~[maxBytes] on disk.
 */
class ServerLogStore(
    private val file: File,
    private val maxBytes: Long = 512 * 1024,
    private val keepTailBytes: Long = 256 * 1024,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun append(line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.isFile && file.length() > maxBytes) rotate()
            file.appendText(line.take(4_000) + "\n")
        }
    }

    fun appendNote(note: String) {
        append("[${clock()}] $note")
    }

    fun tail(maxLines: Int = 400): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val lines = file.readLines()
            if (lines.size <= maxLines) lines else lines.subList(lines.size - maxLines, lines.size)
        }.getOrDefault(emptyList())
    }

    fun clear() {
        runCatching { file.delete() }
    }

    fun sizeBytes(): Long = runCatching { file.length() }.getOrDefault(0L)

    fun path(): String = file.absolutePath

    private fun rotate() {
        val tail = runCatching {
            val length = file.length()
            val start = (length - keepTailBytes).coerceAtLeast(0)
            file.inputStream().use { input ->
                input.skipFully(start)
                input.readBytes()
            }
        }.getOrNull() ?: return
        runCatching {
            file.writeBytes(tail)
        }
    }

    private fun java.io.InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }
}
