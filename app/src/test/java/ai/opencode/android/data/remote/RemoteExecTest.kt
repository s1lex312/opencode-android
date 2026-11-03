package ai.opencode.android.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteExecTest {

    private val token = "abc123"
    private val marker = RemoteExec.marker(token)

    @Test
    fun `marker gives the exit code and is not part of the output`() {
        val output = ExecOutput(token)
        val first = output.append("\r\nhello\r\n")
        assertEquals("hello\n", first)
        val second = output.append("world\r\n\r\n${marker}3__\r\n")
        assertEquals("world\n", second)
        assertEquals(3, output.exitCode)
        assertEquals("hello\nworld", output.text())
    }

    @Test
    fun `a marker split across chunks is held back`() {
        val output = ExecOutput(token)
        output.append("\r\nok\r\n\r\n__OCX_abc")
        assertNull(output.exitCode)
        assertEquals("ok", output.text().substringBefore("\n__OCX").trim())
        output.append("123_0__\r\n")
        assertEquals(0, output.exitCode)
        assertEquals("ok", output.text())
    }

    @Test
    fun `escapes and carriage-return progress are cleaned`() {
        val clean = ExecOutput.clean("\u001B[32mgreen\u001B[0m\r\n10%\r50%\r100%\r\n\u001B]0;title\u0007done")
        assertEquals("green\n100%\ndone", clean)
    }

    @Test
    fun `the gate echo is dropped`() {
        assertEquals("x", ExecOutput.clean("\r\nx"))
    }

    @Test
    fun `output after the marker is ignored`() {
        val output = ExecOutput(token)
        output.append("a\n${marker}1__\nprompt$ ")
        assertEquals(1, output.exitCode)
        assertEquals("", output.append("more"))
        assertEquals("a", output.text())
    }

    @Test
    fun `wrapper gates, merges stderr and prints the status`() {
        val wrapped = RemoteExec.wrap("echo hi", token)
        assertTrue(wrapped.startsWith("IFS= read -r _oc_gate; ( echo hi\n) 2>&1;"))
        assertTrue(wrapped.contains("printf '\\n${marker}%d__\\n' \"\$?\""))
    }

    @Test
    fun `shell quoting survives single quotes`() {
        assertEquals("'it'\\''s'", RemoteExec.quote("it's"))
        assertEquals("'a b'", RemoteExec.quote("a b"))
    }
}
