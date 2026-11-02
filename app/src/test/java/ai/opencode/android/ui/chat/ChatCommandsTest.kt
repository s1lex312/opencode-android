package ai.opencode.android.ui.chat

import ai.opencode.android.data.model.AgentOption
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCommandsTest {

    @Test
    fun `parses name and arguments`() {
        assertEquals(ParsedCommand("rename", "My chat"), parseCommand("/rename My chat"))
        assertEquals(ParsedCommand("compact", ""), parseCommand("  /compact  "))
        assertEquals(ParsedCommand("review", "main\nmore"), parseCommand("/review main\nmore"))
        assertNull(parseCommand("hello /there"))
        assertNull(parseCommand("/"))
    }

    @Test
    fun `every TUI command and alias resolves`() {
        listOf("new", "clear", "sessions", "models", "model", "agent", "mode", "compact", "summarize", "context", "cost",
            "undo", "redo", "fork", "rename", "share", "unshare", "export", "copy", "details", "thinking", "mcp",
            "help", "exit", "quit", "theme", "status", "terminal", "diff", "settings", "permissions", "system").forEach { name ->
            assertTrue("/$name", ClientCommand.find(name) != null)
        }
        assertEquals(ClientCommand.COMPACT, ClientCommand.find("Summarize"))
        assertNull(ClientCommand.find("init"))
        assertNull(ClientCommand.find("review"))
    }

    @Test
    fun `names and aliases are unique`() {
        val all = ClientCommand.entries.flatMap { it.names }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `paths are not commands`() {
        assertTrue(looksLikePath("usr/bin/env"))
        assertTrue(looksLikePath("etc.hosts"))
        assertFalse(looksLikePath("deploy"))
    }

    @Test
    fun `token counts`() {
        assertEquals(128_000L, parseTokenCount("128k"))
        assertEquals(1_000_000L, parseTokenCount("1M"))
        assertEquals(1_500_000L, parseTokenCount("1.5m"))
        assertEquals(200_000L, parseTokenCount("200 000"))
        assertEquals(200_000L, parseTokenCount("200_000"))
        assertNull(parseTokenCount("abc"))
        assertNull(parseTokenCount("10"))
        assertNull(parseTokenCount(""))
    }

    @Test
    fun `compact counts round-trip through the token parser`() {
        listOf(32_000L, 128_000L, 200_000L, 1_000_000L, 2_000_000L).forEach { value ->
            assertEquals(value, parseTokenCount(compactCount(value).lowercase()))
        }
    }

    @Test
    fun `chat override beats model override beats catalogue`() {
        val base = ChatUiState(
            sessionId = "s",
            messages = listOf(ChatMessage("a", Role.ASSISTANT, providerId = "p", model = "m", tokens = TokenUsage(input = 10_000))),
            models = listOf(ModelOption("p", "m", "M", contextLimit = 200_000)),
        )
        assertEquals(200_000L, base.usage!!.limit)
        assertEquals(LimitSource.CATALOGUE, base.usage!!.limitSource)
        val model = base.copy(modelContextLimits = mapOf("p/m" to 100_000L))
        assertEquals(100_000L, model.usage!!.limit)
        assertEquals(LimitSource.MODEL, model.usage!!.limitSource)
        val chat = model.copy(sessionContextLimit = 50_000L)
        assertEquals(50_000L, chat.usage!!.limit)
        assertEquals(LimitSource.CHAT, chat.usage!!.limitSource)
        assertEquals(0.2f, chat.usage!!.fraction!!, 0.0001f)
        assertEquals(80, chat.usage!!.percentLeft)
    }

    @Test
    fun `usage exists before the first answer when the window is known`() {
        val state = ChatUiState(
            sessionId = "s",
            model = ai.opencode.android.data.model.ModelRef("p", "m"),
            models = listOf(ModelOption("p", "m", "M", contextLimit = 64_000)),
        )
        assertEquals(0L, state.usage!!.used)
        assertEquals(64_000L, state.usage!!.limit)
    }

    @Test
    fun `subagents and the effective agent`() {
        val state = ChatUiState(
            sessionId = "s",
            agents = listOf(
                AgentOption("build", mode = "primary"),
                AgentOption("plan", mode = "primary"),
                AgentOption("general", mode = "subagent"),
                AgentOption("reviewer", mode = "all"),
            ),
        )
        assertEquals(listOf("general", "reviewer"), state.subagents.map { it.name })
        assertEquals("build", state.effectiveAgent)
        assertEquals("plan", state.copy(agent = "plan").effectiveAgent)
    }

    @Test
    fun `mention token regex`() {
        val found = ChatViewModel.MENTION_TOKEN.findAll("ask @reviewer to check @src/App.kt, mail a@b.c").map { it.groupValues[1] }.toList()
        assertEquals(listOf("reviewer", "src/App.kt,"), found)
    }

    @Test
    fun `composer modes`() {
        assertEquals(ComposerMode.SHELL, composerMode("!ls -la"))
        assertEquals(ComposerMode.COMMAND, composerMode("/help"))
        assertEquals(ComposerMode.PROMPT, composerMode("fix it"))
    }

    @Test
    fun `directory mentions keep the popup open`() {
        val value = androidx.compose.ui.text.input.TextFieldValue("see @sr", androidx.compose.ui.text.TextRange(7))
        val token = composerToken(value) as ComposerToken.Mention
        val updated = value.withMention(token, "src/", keepOpen = true)
        assertEquals("see @src/", updated.text)
        assertEquals(ComposerToken.Mention("src/", 4, 9), composerToken(updated))
    }

    @Test
    fun `tool headers read like Claude Code`() {
        val bash = ai.opencode.android.data.model.ToolCall("1", "bash")
        val input = kotlinx.serialization.json.buildJsonObject {
            put("command", kotlinx.serialization.json.JsonPrimitive("npm test\necho done"))
        }
        assertEquals(ToolHeader("Bash", "npm test"), toolHeader(bash, input))
        val read = toolHeader(ai.opencode.android.data.model.ToolCall("2", "read"), kotlinx.serialization.json.buildJsonObject {
            put("filePath", kotlinx.serialization.json.JsonPrimitive("/root/app/Main.kt"))
        })
        assertEquals(ToolHeader("Read", "/root/app/Main.kt"), read)
        assertEquals("github · search_issues", toolHeader(ai.opencode.android.data.model.ToolCall("3", "github_search_issues"), null).title)
    }

    @Test
    fun `elapsed time formatting`() {
        assertEquals("9s", formatElapsed(9))
        assertEquals("2m 5s", formatElapsed(125))
        assertEquals("1h 1m", formatElapsed(3660))
    }
}
