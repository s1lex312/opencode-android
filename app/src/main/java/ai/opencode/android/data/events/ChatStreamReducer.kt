package ai.opencode.android.data.events

import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ToolCall
import ai.opencode.android.data.model.ToolStatus

/**
 * Pure reducer that folds live SSE events into the message list currently on screen.
 *
 * The server remains the source of truth: after an execution finishes the ViewModel refetches
 * the message projection, and this reducer only produces a smooth optimistic tail in between.
 * Keeping it pure makes the tricky streaming cases (out-of-order tool updates, legacy cumulative
 * text parts) unit-testable without a server.
 */
object ChatStreamReducer {

    private const val TOOL_PART_PREFIX = "tool-"
    private const val TEXT_PART_PREFIX = "text-"

    fun reduce(messages: List<ChatMessage>, event: OpencodeEvent, sessionId: String): List<ChatMessage>? {
        if (event.sessionId != null && event.sessionId != sessionId) return null
        val working = messages.toMutableList()
        return when (event) {
            is OpencodeEvent.TextDelta ->
                applyText(working, event.messageId, TEXT_PART_PREFIX + event.ordinal, event.delta, append = true)

            is OpencodeEvent.TextCompleted ->
                applyText(working, event.messageId, TEXT_PART_PREFIX + event.ordinal, event.text, append = false)

            is OpencodeEvent.ToolStarted -> applyTool(working, event.messageId, TOOL_PART_PREFIX + event.toolId) {
                it.copy(name = event.name, status = ToolStatus.RUNNING)
            }

            is OpencodeEvent.ToolCalled -> applyTool(working, event.messageId, TOOL_PART_PREFIX + event.toolId) {
                it.copy(status = ToolStatus.RUNNING, input = event.input ?: it.input)
            }

            is OpencodeEvent.ToolSucceeded -> applyTool(working, event.messageId, TOOL_PART_PREFIX + event.toolId) {
                it.copy(
                    status = ToolStatus.COMPLETED,
                    output = event.output ?: it.output,
                    title = event.title ?: it.title,
                )
            }

            is OpencodeEvent.ToolFailed -> applyTool(working, event.messageId, TOOL_PART_PREFIX + event.toolId) {
                it.copy(status = ToolStatus.ERROR, error = event.error)
            }

            is OpencodeEvent.MessageUpserted -> upsertMessage(working, event.message)

            is OpencodeEvent.PartUpserted ->
                applyLegacyPart(working, event.messageId, event.part, event.delta)

            is OpencodeEvent.PartRemoved -> {
                val index = working.indexOfFirst { it.id == event.messageId }
                if (index < 0) {
                    null
                } else {
                    val message = working[index]
                    working[index] = message.copy(parts = message.parts.filterNot { it.id == event.partId })
                    working.toList()
                }
            }

            is OpencodeEvent.MessageRemoved -> {
                if (working.none { it.id == event.messageId }) null
                else working.filterNot { it.id == event.messageId }.toList()
            }

            else -> null
        }
    }

    private fun applyText(
        messages: MutableList<ChatMessage>,
        messageId: String,
        partId: String,
        text: String,
        append: Boolean,
    ): List<ChatMessage> {
        if (text.isEmpty() && append) return messages.toList()
        val index = ensureMessage(messages, messageId)
        val message = messages[index]
        val parts = message.parts.toMutableList()
        val existingIndex = parts.indexOfFirst { it.id == partId }
        val existing = parts.getOrNull(existingIndex) as? MessagePart.Text
        val merged = when {
            existing == null -> text
            append -> existing.text + text
            // The authoritative snapshot replaces the optimistic tail, but never truncates to nothing.
            text.isEmpty() -> existing.text
            text.length >= existing.text.length -> text
            else -> existing.text
        }
        val part = MessagePart.Text(partId, merged)
        if (existingIndex >= 0) parts[existingIndex] = part else parts += part
        messages[index] = message.copy(parts = parts)
        return messages.toList()
    }

    private fun applyTool(
        messages: MutableList<ChatMessage>,
        messageId: String,
        partId: String,
        transform: (ToolCall) -> ToolCall,
    ): List<ChatMessage> {
        val index = ensureMessage(messages, messageId)
        val message = messages[index]
        val parts = message.parts.toMutableList()
        val existingIndex = parts.indexOfFirst { it.id == partId }
        val existing = (parts.getOrNull(existingIndex) as? MessagePart.Tool)?.call
        val call = transform(existing ?: ToolCall(id = partId.removePrefix(TOOL_PART_PREFIX), name = "tool"))
        val part = MessagePart.Tool(partId, call)
        if (existingIndex >= 0) parts[existingIndex] = part else parts += part
        messages[index] = message.copy(parts = parts)
        return messages.toList()
    }

    private fun applyLegacyPart(
        messages: MutableList<ChatMessage>,
        messageId: String,
        incoming: MessagePart,
        delta: String?,
    ): List<ChatMessage> {
        val index = ensureMessage(messages, messageId)
        val message = messages[index]
        val parts = message.parts.toMutableList()
        val existingIndex = parts.indexOfFirst { it.id == incoming.id }
        val existing = parts.getOrNull(existingIndex)
        val merged: MessagePart = when {
            existing is MessagePart.Text && incoming is MessagePart.Text -> {
                val text = when {
                    // Server snapshot already contains the new chunk (cumulative parts).
                    incoming.text.length > existing.text.length && incoming.text.startsWith(existing.text) ->
                        incoming.text
                    // Snapshot lags behind: append the explicit delta exactly once.
                    !delta.isNullOrEmpty() -> existing.text + delta
                    incoming.text.isEmpty() -> existing.text
                    else -> incoming.text
                }
                MessagePart.Text(incoming.id, text)
            }

            existing is MessagePart.Reasoning && incoming is MessagePart.Reasoning ->
                MessagePart.Reasoning(incoming.id, incoming.text.ifEmpty { existing.text })

            existing is MessagePart.Tool && incoming is MessagePart.Tool ->
                MessagePart.Tool(incoming.id, mergeTools(existing.call, incoming.call))

            else -> incoming
        }
        if (existingIndex >= 0) parts[existingIndex] = merged else parts += merged
        messages[index] = message.copy(parts = parts)
        return messages.toList()
    }

    private fun mergeTools(old: ToolCall, new: ToolCall): ToolCall = old.copy(
        name = new.name.takeIf { it.isNotBlank() && it != "tool" } ?: old.name,
        status = if (new.status == ToolStatus.PENDING && old.status != ToolStatus.PENDING) old.status else new.status,
        input = new.input ?: old.input,
        output = new.output ?: old.output,
        title = new.title ?: old.title,
        error = new.error ?: old.error,
    )

    private fun upsertMessage(messages: MutableList<ChatMessage>, incoming: ChatMessage): List<ChatMessage> {
        val index = messages.indexOfFirst { it.id == incoming.id }
        if (index < 0) {
            messages += incoming
        } else {
            val existing = messages[index]
            messages[index] = existing.copy(
                role = incoming.role,
                created = incoming.created.takeIf { it > 0 } ?: existing.created,
                error = incoming.error ?: existing.error,
                model = incoming.model ?: existing.model,
                providerId = incoming.providerId ?: existing.providerId,
                tokens = incoming.tokens ?: existing.tokens,
                cost = incoming.cost ?: existing.cost,
                parts = if (existing.parts.isEmpty()) incoming.parts else existing.parts,
            )
        }
        return messages.toList()
    }

    private fun ensureMessage(messages: MutableList<ChatMessage>, messageId: String): Int {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index >= 0) return index
        messages += ChatMessage(
            id = messageId,
            role = Role.ASSISTANT,
            created = 0L,
        )
        return messages.lastIndex
    }
}
