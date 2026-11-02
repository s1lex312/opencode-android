package ai.opencode.android.data.events

import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.PermissionRequest

/**
 * Normalised server events. Both supported API flavours (opencode 2.x `HttpApi` and the legacy
 * 0.x/1.x REST surface) are translated into this vocabulary by [EventMapper], so the ViewModels
 * only ever deal with one event set.
 */
sealed interface OpencodeEvent {

    val sessionId: String?

    data class TextDelta(
        override val sessionId: String,
        val messageId: String,
        val ordinal: Int,
        val delta: String,
    ) : OpencodeEvent

    data class TextCompleted(
        override val sessionId: String,
        val messageId: String,
        val ordinal: Int,
        val text: String,
    ) : OpencodeEvent

    data class ToolStarted(
        override val sessionId: String,
        val messageId: String,
        val toolId: String,
        val name: String,
    ) : OpencodeEvent

    data class ToolCalled(
        override val sessionId: String,
        val messageId: String,
        val toolId: String,
        val input: String?,
    ) : OpencodeEvent

    data class ToolSucceeded(
        override val sessionId: String,
        val messageId: String,
        val toolId: String,
        val output: String?,
        val title: String? = null,
    ) : OpencodeEvent

    data class ToolFailed(
        override val sessionId: String,
        val messageId: String,
        val toolId: String,
        val error: String,
    ) : OpencodeEvent

    data class Status(
        override val sessionId: String,
        val busy: Boolean,
        val retryMessage: String? = null,
    ) : OpencodeEvent

    data class Idle(override val sessionId: String) : OpencodeEvent

    data class SessionFailed(
        override val sessionId: String?,
        val message: String,
    ) : OpencodeEvent

    data class PermissionAsked(val request: PermissionRequest) : OpencodeEvent {
        override val sessionId: String get() = request.sessionId
    }

    data class PermissionResolved(
        override val sessionId: String,
        val permissionId: String,
    ) : OpencodeEvent

    data class MessageUpserted(
        override val sessionId: String,
        val message: ChatMessage,
    ) : OpencodeEvent

    data class PartUpserted(
        override val sessionId: String,
        val messageId: String,
        val part: MessagePart,
        val delta: String? = null,
    ) : OpencodeEvent

    data class PartRemoved(
        override val sessionId: String,
        val messageId: String,
        val partId: String,
    ) : OpencodeEvent

    data class MessageRemoved(
        override val sessionId: String,
        val messageId: String,
    ) : OpencodeEvent

    /** Live state for this session is no longer authoritative; refetch messages. */
    data class MessagesInvalidated(override val sessionId: String?) : OpencodeEvent

    /** Session list (titles, ordering, existence) changed. */
    data object SessionsChanged : OpencodeEvent {
        override val sessionId: String? get() = null
    }

    data class DiffChanged(
        override val sessionId: String,
        val files: List<FileDiffPayload>? = null,
    ) : OpencodeEvent

    data object Ignored : OpencodeEvent {
        override val sessionId: String? get() = null
    }
}
