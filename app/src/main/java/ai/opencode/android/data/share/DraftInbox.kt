package ai.opencode.android.data.share

import ai.opencode.android.data.model.PromptAttachment
import java.util.concurrent.ConcurrentHashMap

/** Text and attachments waiting for a chat's composer (from the share sheet). */
data class Draft(val text: String, val attachments: List<PromptAttachment> = emptyList(), val send: Boolean = false)

/** Hands drafts to a chat screen that is about to open; each draft is taken once. */
class DraftInbox {
    private val drafts = ConcurrentHashMap<String, Draft>()

    fun put(sessionId: String, draft: Draft) {
        drafts[sessionId] = draft
    }

    fun take(sessionId: String): Draft? = drafts.remove(sessionId)
}

/** What another app shared with us (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`), before it becomes a [Draft]. */
data class SharedContent(val text: String, val uris: List<android.net.Uri> = emptyList(), val subject: String? = null)
