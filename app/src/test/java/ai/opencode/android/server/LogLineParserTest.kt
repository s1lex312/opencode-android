package ai.opencode.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogLineParserTest {

    @Test
    fun `detects a listening server and its port`() {
        val parsed = LogLineParser.parse("opencode server listening on http://127.0.0.1:4096")
        assertTrue(parsed.ready)
        assertEquals(4096, parsed.port)
        assertEquals(LogLineParser.Level.INFO, parsed.level)
        assertFalse(parsed.fatal)
    }

    @Test
    fun `detects address-in-use as fatal`() {
        val parsed = LogLineParser.parse("Error: listen EADDRINUSE: address already in use 127.0.0.1:4096")
        assertTrue(parsed.fatal)
        assertEquals(LogLineParser.Level.ERROR, parsed.level)
    }

    @Test
    fun `plain errors are errors but not fatal`() {
        val parsed = LogLineParser.parse("error: model not configured")
        assertEquals(LogLineParser.Level.ERROR, parsed.level)
        assertFalse(parsed.fatal)
    }

    @Test
    fun `warnings are recognised`() {
        assertEquals(LogLineParser.Level.WARN, LogLineParser.parse("warning: config file missing").level)
    }

    @Test
    fun `parses exit codes`() {
        val parsed = LogLineParser.parse("opencode exited with code 137")
        assertEquals(137, parsed.exitCode)
    }

    @Test
    fun `proot failures are fatal`() {
        assertTrue(LogLineParser.parse("proot error: execve(\"/bin/sh\"): No such file or directory").fatal)
        assertTrue(LogLineParser.parse("proot info: permission denied").fatal)
    }

    @Test
    fun `ordinary info lines stay info`() {
        val parsed = LogLineParser.parse("INFO service=session id=ses_123 created")
        assertEquals(LogLineParser.Level.INFO, parsed.level)
        assertFalse(parsed.ready)
        assertEquals(null, parsed.port)
    }
}
