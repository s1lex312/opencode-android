package ai.opencode.android.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    @Test
    fun `dispatches a simple data frame on blank line`() {
        val parser = SseParser()

        assertNull(parser.feed("data: {\"type\":\"session.idle\"}"))
        val event = parser.feed("")

        assertNotNull(event)
        assertEquals("{\"type\":\"session.idle\"}", event!!.data)
        assertNull(event.type)
    }

    @Test
    fun `joins multi-line data with newlines`() {
        val parser = SseParser()
        parser.feed("event: message")
        parser.feed("data: line1")
        parser.feed("data: line2")
        val event = parser.feed("")

        assertEquals("message", event!!.type)
        assertEquals("line1\nline2", event.data)
    }

    @Test
    fun `ignores heartbeat comments and retry hints`() {
        val parser = SseParser()

        assertNull(parser.feed(": heartbeat"))
        assertNull(parser.feed("retry: 4000"))
        assertNull(parser.feed(""))

        assertNull(parser.feed("data: ok"))
        val event = parser.feed("")
        assertEquals("ok", event!!.data)
        assertNull(event.type)
    }

    @Test
    fun `handles value without leading space and without colon`() {
        val parser = SseParser()
        parser.feed("data:no-space")
        assertEquals("no-space", parser.feed("")!!.data)

        val parser2 = SseParser()
        parser2.feed("data")
        assertEquals("", parser2.feed("")!!.data)
    }

    @Test
    fun `flush emits buffered event without trailing blank line`() {
        val parser = SseParser()
        parser.feed("data: tail")

        val event = parser.flush()

        assertEquals("tail", event!!.data)
        assertNull(parser.flush())
    }

    @Test
    fun `carries the last event id across frames`() {
        val parser = SseParser()
        parser.feed("id: 42")
        parser.feed("data: first")
        assertEquals("42", parser.feed("")!!.id)

        parser.feed("data: second")
        assertEquals("42", parser.feed("")!!.id)
    }

    @Test
    fun `reset clears partial state`() {
        val parser = SseParser()
        parser.feed("data: partial")
        parser.reset()

        assertNull(parser.flush())
    }
}
