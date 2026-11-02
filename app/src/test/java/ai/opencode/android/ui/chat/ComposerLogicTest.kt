package ai.opencode.android.ui.chat

import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.ModelOption
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.TokenUsage
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerLogicTest {

    private fun at(text: String, caret: Int = text.length) = TextFieldValue(text, TextRange(caret))

    @Test
    fun `detects a mention under the caret`() {
        val token = composerToken(at("look at @src/Ma"))
        assertEquals(ComposerToken.Mention("src/Ma", 8, 15), token)
    }

    @Test
    fun `email-like text is not a mention`() {
        assertEquals(ComposerToken.None, composerToken(at("mail me@host")))
    }

    @Test
    fun `slash command only while typing the first word`() {
        assertEquals(ComposerToken.Command("comp"), composerToken(at("/comp")))
        assertEquals(ComposerToken.None, composerToken(at("/compact now")))
        assertEquals(ComposerToken.None, composerToken(at("use a/b path")))
    }

    @Test
    fun `mention replacement puts the caret after the path`() {
        val value = at("see @Ma and more", caret = 7)
        val token = composerToken(value) as ComposerToken.Mention
        val replaced = value.withMention(token, "src/Main.kt")
        assertEquals("see @src/Main.kt  and more", replaced.text)
        assertEquals(17, replaced.selection.end)
    }

    @Test
    fun `usage uses the last turn and the catalogue limit`() {
        val state = ChatUiState(
            sessionId = "s",
            messages = listOf(
                ChatMessage("m1", Role.ASSISTANT, providerId = "p", model = "x", tokens = TokenUsage(input = 1000, output = 200), cost = 0.01),
                ChatMessage("m2", Role.USER),
                ChatMessage("m3", Role.ASSISTANT, providerId = "p", model = "x", tokens = TokenUsage(input = 50, output = 100, cacheRead = 1850), cost = 0.02),
            ),
            models = listOf(ModelOption("p", "x", "X", contextLimit = 10_000)),
        )
        val usage = state.usage!!
        assertEquals(2000, usage.used)
        assertEquals(0.2f, usage.fraction!!, 0.0001f)
        assertEquals(0.03, usage.cost, 1e-9)
        assertEquals(1050, usage.total.input)
    }

    @Test
    fun `reverted tail is hidden`() {
        val state = ChatUiState(
            sessionId = "s",
            messages = listOf(ChatMessage("a", Role.USER), ChatMessage("b", Role.ASSISTANT), ChatMessage("c", Role.USER)),
            revertMessageId = "b",
        )
        assertEquals(listOf("a"), state.visibleMessages.map { it.id })
        assertEquals(2, state.revertedCount)
        assertNull(ChatUiState(sessionId = "s").usage)
    }

    @Test
    fun `primary agents put build and plan first`() {
        val state = ChatUiState(
            sessionId = "s",
            agents = listOf(
                ai.opencode.android.data.model.AgentOption("docs", mode = "primary"),
                ai.opencode.android.data.model.AgentOption("plan", mode = "primary"),
                ai.opencode.android.data.model.AgentOption("general", mode = "subagent"),
                ai.opencode.android.data.model.AgentOption("build", mode = "primary"),
            ),
        )
        assertEquals(listOf("build", "plan", "docs"), state.primaryAgents.map { it.name })
        assertTrue(state.primaryAgents.none { it.name == "general" })
    }
}
