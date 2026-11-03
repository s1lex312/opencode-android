package ai.opencode.android.ui.chat

import ai.opencode.android.settings.BackgroundTask
import ai.opencode.android.settings.Hook
import ai.opencode.android.settings.HookEvent
import ai.opencode.android.settings.Snippet
import ai.opencode.android.settings.TaskStatus
import ai.opencode.android.settings.Workbench
import ai.opencode.android.ui.tools.EnvPackage
import ai.opencode.android.ui.tools.EnvScripts
import ai.opencode.android.ui.tools.EnvTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SnippetsTest {

    @Test
    fun `arguments and positional placeholders`() {
        assertEquals("Explain src/App.kt now", Snippets.expand("Explain \$ARGUMENTS now", "src/App.kt"))
        assertEquals("from a to b c", Snippets.expand("from \$1 to \$2", "a \"b c\""))
        assertEquals("x  y", Snippets.expand("x \$3 y", "one"))
        assertEquals("Review the diff.\n\nfocus on auth", Snippets.expand("Review the diff.", "focus on auth"))
        assertEquals("Review the diff.", Snippets.expand("Review the diff.", "  "))
    }

    @Test
    fun `argument splitting`() {
        assertEquals(listOf("a", "b c", "d'e"), Snippets.splitArguments("a 'b c' \"d'e\""))
        assertEquals(emptyList<String>(), Snippets.splitArguments("   "))
    }

    @Test
    fun `names`() {
        assertTrue(Snippets.isValidName("commit-msg"))
        assertFalse(Snippets.isValidName("Commit"))
        assertFalse(Snippets.isValidName("-x"))
        assertEquals("my-prompt_2", Snippets.normalizeName("  My Prompt_2!! "))
    }

    @Test
    fun `new client commands keep names unique and resolvable`() {
        listOf("rewind", "checkpoints", "git", "bg", "background", "tasks", "snippets", "hooks", "stats", "env").forEach {
            assertTrue(it, ClientCommand.find(it) != null)
        }
        val all = ClientCommand.entries.flatMap { it.names }
        assertEquals(all.size, all.toSet().size)
        assertNull(ClientCommand.find("commit"))
    }

    @Test
    fun `workbench persists everything`() = runBlocking {
        val dir = Files.createTempDirectory("wb").toFile()
        try {
            val file = java.io.File(dir, "workbench.json")
            val first = Workbench(file)
            first.update {
                it.copy(
                    hooks = listOf(Hook("h", "fmt", HookEvent.FILE_EDITED, "prettier", matcher = "*.ts", feedback = true)),
                    snippets = listOf(Snippet("s", "explain", "Explain \$ARGUMENTS")),
                    budget = it.budget.copy(daily = 2.5, block = true),
                    tasks = listOf(BackgroundTask("t", "ses", "do it", 1L, TaskStatus.DONE, 2L, "ok")),
                )
            }
            val second = Workbench(file)
            assertEquals(first.current, second.current)
            assertEquals(HookEvent.FILE_EDITED, second.current.hooks.single().event)
            assertTrue(file.readText().contains("\"file_edited\""))
            // Corrupt files fall back to defaults instead of crashing.
            file.writeText("{broken")
            assertEquals(0, Workbench(file).current.hooks.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `environment check parsing and install script`() {
        val check = EnvScripts.parseCheck("node=v22.11.0\nnpm=10.9.0\nnpx=\npython3=Python 3.10.12\ngit=\npm=apt-get\nuid=0")
        assertEquals("v22.11.0", check.versions[EnvTool.NODE])
        assertNull(check.versions[EnvTool.NPX])
        assertNull(check.versions[EnvTool.GIT])
        assertEquals("apt-get", check.packageManager)
        assertTrue(check.root)
        val script = EnvScripts.install(setOf(EnvPackage.NODE, EnvPackage.GIT))
        assertTrue(script.contains("apt-get install -y --no-install-recommends ca-certificates curl xz-utils git openssh-client"))
        assertTrue(script.contains("nodejs.org/dist/${EnvScripts.NODE_VERSION}/node-${EnvScripts.NODE_VERSION}-linux-\$arch.tar.xz"))
        assertFalse(script.contains("pip install"))
        assertTrue(EnvScripts.install(setOf(EnvPackage.PYTHON)).contains("python3 -m pip install --upgrade uv"))
        assertTrue(EnvScripts.CHECK.contains("for t in node npm npx python3 pip3 uv uvx git curl; do"))
    }
}
