package ai.opencode.android.data.mcp

import ai.opencode.android.data.model.McpServerInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpWarmupTest {

    private fun prepareLine(command: List<String>): String? = McpWarmup.script(command)?.lines()?.last { it.isNotBlank() }

    @Test
    fun `npx installs the package spec without starting the server`() {
        assertEquals(
            "npx --yes --package=@modelcontextprotocol/server-filesystem -- node -e 0 </dev/null",
            prepareLine(listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/root", "/sdcard")),
        )
        assertEquals(
            "npx --yes --package=@playwright/mcp@latest -- node -e 0 </dev/null",
            prepareLine(listOf("npx", "-y", "@playwright/mcp@latest", "--headless")),
        )
        // With --package the positional word is a binary, not a package.
        assertEquals(
            "npx --yes --package=a --package=b -- node -e 0 </dev/null",
            prepareLine(listOf("npx", "-p", "a", "--package=b", "run-it", "--flag")),
        )
    }

    @Test
    fun `uvx builds the tool environment, keeping python and extra packages`() {
        assertEquals("uvx --from mcp-server-fetch python -c pass </dev/null", prepareLine(listOf("uvx", "mcp-server-fetch")))
        assertEquals(
            "uvx --python 3.12 --with httpx --from git+https://x/y.git python -c pass </dev/null",
            prepareLine(listOf("uvx", "--python", "3.12", "--with", "httpx", "--from", "git+https://x/y.git", "y-server", "--port", "1")),
        )
    }

    @Test
    fun `other launchers are left alone, and the script checks the tool and clears android variables`() {
        assertNull(McpWarmup.script(listOf("docker", "run", "x")))
        assertNull(McpWarmup.script(listOf("node", "server.js")))
        assertNull(McpWarmup.script(listOf("npx")))
        val script = McpWarmup.script(listOf("/usr/bin/npx", "-y", "pkg"))!!
        assertTrue(script.startsWith("unset ANDROID_DATA ANDROID_ROOT"))
        assertTrue(script.contains("command -v npx"))
    }

    @Test
    fun `the monitor keeps status changes, connect times and the last error`() = runBlocking {
        var now = 0L
        val monitor = McpMonitor { now }
        monitor.observe(listOf(McpServerInfo("fetch", McpServerInfo.Status.PENDING)))
        monitor.observe(listOf(McpServerInfo("fetch", McpServerInfo.Status.PENDING)))
        now = 31_000
        monitor.observe(listOf(McpServerInfo("fetch", McpServerInfo.Status.FAILED, error = "Connection closed")))
        now = 40_000
        monitor.timed("fetch", { now += 2_500; "ok" }) { null }
        monitor.observe(listOf(McpServerInfo("fetch", McpServerInfo.Status.CONNECTED)))
        val health = monitor.health.value.getValue("fetch")
        assertEquals(
            listOf(McpServerInfo.Status.PENDING, McpServerInfo.Status.FAILED, McpServerInfo.Status.CONNECTED),
            health.changes.map { it.status },
        )
        assertEquals("Connection closed", health.lastError)
        assertEquals(31_000L, health.lastErrorAt)
        assertEquals(2_500L, health.lastConnect?.millis)
        assertTrue(health.lastConnect!!.ok)
        assertEquals(42_500L, health.since)
    }
}
