package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.data.model.PromptAttachment
import ai.opencode.android.ui.text.UiText
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Reads picked images/files into `data:` URLs the server accepts as file parts. */
object Attachments {

    /** Raw size cap; base64 adds a third, and providers reject huge images anyway. */
    const val MAX_BYTES = 8L * 1024 * 1024

    /** Longest edge of images re-encoded before upload; keeps screenshots readable but small. */
    private const val MAX_IMAGE_EDGE = 2048

    sealed interface Result {
        data class Ok(val attachment: PromptAttachment) : Result
        data class Err(val message: UiText) : Result
    }

    suspend fun read(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (name, size) = queryMeta(context, uri)
        val mime = resolver.getType(uri) ?: guessMime(name)
        if (size != null && size > MAX_BYTES && !mime.startsWith("image/")) {
            return@withContext Result.Err(UiText.of(R.string.chat_attach_too_large, (MAX_BYTES / (1024 * 1024)).toInt()))
        }
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return@withContext Result.Err(UiText.of(R.string.chat_attach_failed))

        val (payload, finalMime) = if (mime.startsWith("image/") && mime != "image/gif") {
            downscale(bytes) ?: (bytes to mime)
        } else {
            bytes to mime
        }
        if (payload.size > MAX_BYTES) {
            return@withContext Result.Err(UiText.of(R.string.chat_attach_too_large, (MAX_BYTES / (1024 * 1024)).toInt()))
        }
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        Result.Ok(
            PromptAttachment(
                id = "${System.nanoTime()}",
                name = name ?: "attachment",
                mime = finalMime,
                dataUrl = "data:$finalMime;base64,$encoded",
                sizeBytes = payload.size.toLong(),
            ),
        )
    }

    /** Decodes a small preview from a `data:` URL; `null` for non-images. */
    fun thumbnail(attachment: PromptAttachment, edge: Int = 160): Bitmap? {
        if (!attachment.isImage) return null
        val bytes = runCatching { Base64.decode(attachment.dataUrl.substringAfter(','), Base64.NO_WRAP) }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= edge && bounds.outHeight / (sample * 2) >= edge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun downscale(bytes: ByteArray): Pair<ByteArray, String>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        if (longest <= MAX_IMAGE_EDGE && bytes.size <= MAX_BYTES / 2) return null
        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = MAX_IMAGE_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        return out.toByteArray() to "image/jpeg"
    }

    private fun queryMeta(context: Context, uri: Uri): Pair<String?, Long?> = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null to null
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                name to size
            }
    }.getOrNull() ?: (uri.lastPathSegment to null)

    private fun guessMime(name: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in TEXT_EXTENSIONS) "text/plain" else "application/octet-stream"
    }

    private val TEXT_EXTENSIONS = setOf("kt", "kts", "java", "py", "js", "ts", "tsx", "rs", "go", "md", "json", "yaml", "yml", "toml", "sh", "c", "h", "cpp", "log")
}
