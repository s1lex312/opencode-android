package ai.opencode.android.data.events

import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ToolCall
import ai.opencode.android.data.model.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamReducerTest {

    private val sessionId = "ses_1"

    private fun assistantMessage(vararg parts: MessagePart) = ChatMessage(
        id = "msg_1",
        role = Role.ASSISTANT,
        parts = parts.toList(),
    )

    @Test
    fun `appends text deltas to the same part`() {
        var messages = emptyList<ChatMessage>()

        messages = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.TextDelta(sessionId, "msg_1", 0, "Hel"),
            sessionId,
        )!!
        messages = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.TextDelta(sessionId, "msg_1", 0, "lo"),
            sessionId,
        )!!

        val text = messages.single().parts.single() as MessagePart.Text
        assertEquals("Hello", text.text)
    }

    @Test
    fun `completed text is authoritative`() {
        val messages = listOf(assistantMessage(MessagePart.Text("text-0", "Hel")))

        val updated = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.TextCompleted(sessionId, "msg_1", 0, "Hello world"),
            sessionId,
        )!!

        assertEquals("Hello world", (updated.single().parts.single() as MessagePart.Text).text)
    }

    @Test
    fun `creates the assistant message when the event arrives first`() {
        val updated = ChatStreamReducer.reduce(
            emptyList(),
            OpencodeEvent.TextDelta(sessionId, "msg_9", 1, "second part"),
            sessionId,
        )!!

        assertEquals(1, updated.size)
        assertEquals(Role.ASSISTANT, updated.single().role)
        assertEquals("text-1", updated.single().parts.single().id)
    }

    @Test
    fun `tool lifecycle is merged into one card`() {
        var messages = ChatStreamReducer.reduce(
            emptyList(),
            OpencodeEvent.ToolStarted(sessionId, "msg_1", "call_1", "bash"),
            sessionId,
        )!!
        messages = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.ToolCalled(sessionId, "msg_1", "call_1", "{\"command\":\"ls\"}"),
            sessionId,
        )!!
        messages = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.ToolSucceeded(sessionId, "msg_1", "call_1", "a.txt", "Listed"),
            sessionId,
        )!!

        val call = (messages.single().parts.single() as MessagePart.Tool).call
        assertEquals("bash", call.name)
        assertEquals(ToolStatus.COMPLETED, call.status)
        assertEquals("a.txt", call.output)
        assertEquals("Listed", call.title)
        assertTrue(call.input!!.contains("ls"))
    }

    @Test
    fun `tool failure keeps the input`() {
        var messages = ChatStreamReducer.reduce(
            emptyList(),
            OpencodeEvent.ToolCalled(sessionId, "msg_1", "call_2", "{\"path\":\"x\"}"),
            sessionId,
        )!!
        messages = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.ToolFailed(sessionId, "msg_1", "call_2", "boom"),
            sessionId,
        )!!

        val call = (messages.single().parts.single() as MessagePart.Tool).call
        assertEquals(ToolStatus.ERROR, call.status)
        assertEquals("boom", call.error)
        assertTrue(call.input!!.contains("path"))
    }

    @Test
    fun `events for other sessions are ignored`() {
        val result = ChatStreamReducer.reduce(
            emptyList(),
            OpencodeEvent.TextDelta("ses_other", "msg_1", 0, "nope"),
            sessionId,
        )
        assertNull(result)
    }

    @Test
    fun `legacy cumulative text part replaces the optimistic tail`() {
        val messages = listOf(assistantMessage(MessagePart.Text("prt_1", "Hello")))

        val updated = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.PartUpserted(
                sessionId = sessionId,
                messageId = "msg_1",
                part = MessagePart.Text("prt_1", "Hello world"),
                delta = " world",
            ),
            sessionId,
        )!!

        assertEquals("Hello world", (updated.single().parts.single() as MessagePart.Text).text)
    }

    @Test
    fun `legacy delta appends when the snapshot lags`() {
        val messages = listOf(assistantMessage(MessagePart.Text("prt_1", "Hello")))

        val updated = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.PartUpserted(
                sessionId = sessionId,
                messageId = "msg_1",
                part = MessagePart.Text("prt_1", "Hello"),
                delta = "!",
            ),
            sessionId,
        )!!

        assertEquals("Hello!", (updated.single().parts.single() as MessagePart.Text).text)
    }

    @Test
    fun `message upsert does not drop already streamed parts`() {
        val messages = listOf(assistantMessage(MessagePart.Text("prt_1", "streamed")))

        val updated = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.MessageUpserted(
                sessionId = sessionId,
                message = ChatMessage(id = "msg_1", role = Role.ASSISTANT, created = 123, model = "m"),
            ),
            sessionId,
        )!!

        assertEquals("streamed", (updated.single().parts.single() as MessagePart.Text).text)
        assertEquals(123L, updated.single().created)
        assertEquals("m", updated.single().model)
    }

    @Test
    fun `part removal and message removal work`() {
        val messages = listOf(
            assistantMessage(
                MessagePart.Text("prt_1", "a"),
                MessagePart.Tool("tool-x", ToolCall(id = "call", name = "bash")),
            ),
        )

        val afterPartRemoval = ChatStreamReducer.reduce(
            messages,
            OpencodeEvent.PartRemoved(sessionId, "msg_1", "tool-x"),
            sessionId,
        )!!
        assertEquals(1, afterPartRemoval.single().parts.size)

        val afterMessageRemoval = ChatStreamReducer.reduce(
            afterPartRemoval,
            OpencodeEvent.MessageRemoved(sessionId, "msg_1"),
            sessionId,
        )!!
        assertTrue(afterMessageRemoval.isEmpty())
    }

    @Test
    fun `status events do not change the transcript`() {
        val result = ChatStreamReducer.reduce(
            emptyList(),
            OpencodeEvent.Status(sessionId, busy = true),
            sessionId,
        )
        assertNull(result)
    }
}
