package ai.opencode.android.server.provision

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 helpers used to verify downloaded environment bundles. */
object Digests {

    private const val CHUNK = 64 * 1024

    fun sha256(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(file: File): String = file.inputStream().buffered(CHUNK).use { sha256(it) }

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    fun toHex(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    /** Comparison that tolerates formatting differences in published hashes. */
    fun matches(expected: String, actual: String): Boolean =
        expected.replace(":", "").replace(" ", "").trim().lowercase() ==
            actual.replace(":", "").replace(" ", "").trim().lowercase()

    private const val HEX = "0123456789abcdef"
}
