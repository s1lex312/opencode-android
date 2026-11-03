package ai.opencode.android.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PluginManifestTest {

    private fun rejects(text: String, reason: String) {
        try {
            PluginManager.parse(text)
            fail("accepted: $text")
        } catch (e: PluginException) {
            assertTrue("${e.message} should mention $reason", e.message!!.contains(reason, ignoreCase = true))
        }
    }

    @Test
    fun `manifests are validated before anything is installed`() {
        rejects("""{"format":"other","id":"x1","name":"X","snippets":[{"name":"a","body":"b"}]}""", "format")
        rejects("""{"id":"Bad Id","name":"X","snippets":[{"name":"a","body":"b"}]}""", "id")
        rejects("""{"id":"ok","name":"X"}""", "adds nothing")
        rejects("""{"id":"ok","name":"X","mcp":{"s":{"command":["x"]}}}""", "local or remote")
        rejects("""{"id":"ok","name":"X","mcp":{"bad name":{"type":"remote","url":"https://x"}}}""", "name")
        rejects("""{"id":"ok","name":"X","version":2,"snippets":[{"name":"a","body":"b"}]}""", "newer app")
        rejects("not json", "manifest")
    }

    @Test
    fun `permissions list programs, hosts and hooks`() {
        val manifest = PluginManager.parse(
            """
            {"id":"demo","name":"Demo",
             "mcp":{"a":{"type":"local","command":["npx","-y","pkg"]},"b":{"type":"remote","url":"https://mcp.example.com/mcp"}},
             "agent":{"rev":{"prompt":"x"}},"command":{"review":{"template":"x"}},
             "systemPrompts":[{"name":"p","text":"t"}],
             "snippets":[{"name":"s","body":"b"}],
             "hooks":[{"name":"fmt","event":"file_edited","command":"prettier --write ${'$'}FILE"}]}
            """.trimIndent(),
        )
        val p = PluginManager.permissions(manifest)
        assertEquals(listOf("npx -y pkg", "prettier --write \$FILE"), p.programs)
        assertEquals(listOf("mcp.example.com"), p.hosts)
        assertEquals(listOf("rev"), p.agents)
        assertEquals(listOf("review"), p.commands)
        assertEquals(1, p.prompts)
        assertEquals(listOf("file_edited: fmt"), p.hooks)
    }

    @Test
    fun `a sha256 pin can ride on the link`() {
        assertEquals("https://x/p.json" to "abc", PluginManager.splitPin("https://x/p.json#sha256=ABC"))
        assertEquals("https://x/p.json" to null, PluginManager.splitPin("https://x/p.json"))
    }

    @Test
    fun `every plugin in the repository catalogue parses and matches its digest`() {
        val dir = listOf(File("../plugins"), File("plugins")).first { it.isDirectory }
        val index = Json.parseToJsonElement(File(dir, "index.json").readText()) as JsonObject
        val entries = index["plugins"]!!.jsonArray
        assertTrue(entries.isNotEmpty())
        entries.forEach { element ->
            val entry = element.jsonObject
            val name = entry["url"]!!.jsonPrimitive.content.substringAfterLast('/')
            val bytes = File(dir, name).readBytes().let { raw -> raw.decodeToString().replace("\r\n", "\n").toByteArray() }
            assertEquals(name, entry["sha256"]!!.jsonPrimitive.content, PluginManager.sha256(bytes))
            assertEquals(entry["id"]!!.jsonPrimitive.content, PluginManager.parse(bytes.decodeToString()).id)
        }
    }
}
