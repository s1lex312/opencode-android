package ai.opencode.android.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBufferTest {

    @Test
    fun `prints lines and handles carriage return`() {
        val buffer = TerminalBuffer(cols = 20, rows = 4)
        buffer.write("hello\r\nworld\rW")
        assertEquals("hello\nWorld", buffer.text())
    }

    @Test
    fun `erase line and cursor movement`() {
        val buffer = TerminalBuffer(cols = 20, rows = 4)
        buffer.write("abcdef\u001b[3D\u001b[K")
        assertEquals("abc", buffer.text())
        buffer.write("\u001b[2;5HX")
        assertEquals("abc\n    X", buffer.text())
    }

    @Test
    fun `colours are parsed and not printed`() {
        val buffer = TerminalBuffer(cols = 20, rows = 2)
        buffer.write("\u001b[1;32mok\u001b[0m \u001b[38;5;208mx\u001b[m")
        assertEquals("ok x", buffer.text())
        assertEquals(2, buffer.line(buffer.lineCount - 2)[0].style.fg)
        assertTrue(buffer.line(buffer.lineCount - 2)[0].style.bold)
        assertEquals(208, buffer.line(buffer.lineCount - 2)[3].style.fg)
    }

    @Test
    fun `scrolls into scrollback and clears screen`() {
        val buffer = TerminalBuffer(cols = 10, rows = 2)
        buffer.write("1\r\n2\r\n3\r\n")
        assertEquals(4, buffer.lineCount)
        buffer.write("\u001b[2J\u001b[H$ ")
        assertEquals("1\n2\n$", buffer.text())
    }

    @Test
    fun `alternate screen restores the main one`() {
        val buffer = TerminalBuffer(cols = 10, rows = 3)
        buffer.write("shell")
        buffer.write("\u001b[?1049hvim\u001b[?1049l")
        assertEquals("shell", buffer.text())
    }

    @Test
    fun `osc title sequences are swallowed`() {
        val buffer = TerminalBuffer(cols = 20, rows = 2)
        buffer.write("\u001b]0;user@host: ~\u0007$ ls")
        assertEquals("$ ls", buffer.text())
    }

    @Test
    fun `wraps at the right margin`() {
        val buffer = TerminalBuffer(cols = 4, rows = 3)
        buffer.write("abcdef")
        assertEquals("abcd\nef", buffer.text())
    }
}
