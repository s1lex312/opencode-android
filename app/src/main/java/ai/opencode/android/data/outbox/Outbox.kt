package ai.opencode.android.data.outbox

import ai.opencode.android.data.model.PromptAttachment
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredAttachment(val id: String, val name: String, val mime: String, val dataUrl: String, val sizeBytes: Long)

@Serializable
data class StoredPrompt(
    val text: String,
    val attachments: List<StoredAttachment> = emptyList(),
    val mentions: List<String> = emptyList(),
    val agents: List<String> = emptyList(),
) {
    fun attachmentModels(): List<PromptAttachment> = attachments.map { PromptAttachment(it.id, it.name, it.mime, it.dataUrl, it.sizeBytes) }

    companion object {
        fun of(text: String, attachments: List<PromptAttachment>, mentions: List<String>, agents: List<String>) = StoredPrompt(
            text = text,
            attachments = attachments.map { StoredAttachment(it.id, it.name, it.mime, it.dataUrl, it.sizeBytes) },
            mentions = mentions,
            agents = agents,
        )
    }
}

/**
 * Prompts waiting to be sent, one file per chat, so a prompt typed in a tunnel or while the server
 * restarts survives the app being killed. Attachments are kept up to [MAX_ATTACHMENT_BYTES] per chat.
 */
class Outbox(context: Context) {

    private val dir = File(context.filesDir, "outbox")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(StoredPrompt.serializer())

    private fun file(sessionId: String) = File(dir, sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun load(sessionId: String): List<StoredPrompt> =
        runCatching { json.decodeFromString(serializer, file(sessionId).readText()) }.getOrDefault(emptyList())

    fun save(sessionId: String, prompts: List<StoredPrompt>) {
        runCatching {
            val target = file(sessionId)
            if (prompts.isEmpty()) {
                target.delete()
                return
            }
            var budget = MAX_ATTACHMENT_BYTES
            val bounded = prompts.map { prompt ->
                prompt.copy(
                    attachments = prompt.attachments.filter { attachment ->
                        (attachment.dataUrl.length.toLong() <= budget).also { fits -> if (fits) budget -= attachment.dataUrl.length }
                    },
                )
            }
            dir.mkdirs()
            target.writeText(json.encodeToString(serializer, bounded))
        }
    }

    /** Chats with prompts waiting, for the sessions list. */
    fun pendingSessions(): Set<String> = dir.listFiles()?.map { it.nameWithoutExtension }?.toSet().orEmpty()

    companion object {
        const val MAX_ATTACHMENT_BYTES = 8L * 1024 * 1024
    }
}
