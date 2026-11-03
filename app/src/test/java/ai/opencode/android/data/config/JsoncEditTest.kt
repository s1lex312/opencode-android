package ai.opencode.android.data.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsoncEditTest {

    private fun parsed(text: String) = Json.parseToJsonElement(Jsonc.strip(text)) as JsonObject

    @Test
    fun `a middle member goes with its whole line and comma`() {
        val text = "{\n  \"a\": 1,\n  \"b\": {\"x\": [1, 2]},\n  \"c\": 3\n}"
        assertEquals("{\n  \"a\": 1,\n  \"c\": 3\n}", JsoncEdit.remove(text, listOf("b")))
    }

    @Test
    fun `the last member takes the previous comma so no trailing comma is left`() {
        val text = "{\n  \"a\": 1,\n  \"b\": 2\n}"
        val result = JsoncEdit.remove(text, listOf("b"))!!
        assertEquals("{\n  \"a\": 1\n}", result)
        Json.parseToJsonElement(result)
    }

    @Test
    fun `the only member leaves an empty object`() {
        assertEquals("{ }", JsoncEdit.remove("{ \"mcp\": {\"a\": 1} }", listOf("mcp"))?.replace("  ", " "))
    }

    @Test
    fun `nested keys in jsonc keep comments, urls and the rest of the formatting`() {
        val text = """
            {
              "${'$'}schema": "https://opencode.ai/config.json", // schema
              /* providers */
              "provider": {
                "keep": { "options": { "baseURL": "http://a//b" } },
                "drop": { "npm": "x", "options": { "baseURL": "http://c/*not a comment*/" } }, // trailing
              },
              "mcp": {}
            }
        """.trimIndent()
        val result = JsoncEdit.remove(text, listOf("provider", "drop"))!!
        assertTrue(result.contains("// schema"))
        assertTrue(result.contains("/* providers */"))
        assertTrue(result.contains("\"keep\""))
        val providers = parsed(result)["provider"] as JsonObject
        assertEquals(setOf("keep"), providers.keys)
        assertTrue(parsed(result).containsKey("mcp"))
    }

    @Test
    fun `escaped keys match and absent paths or broken text give null`() {
        val text = """{"a\"b": 1, "c": 2}"""
        assertEquals(setOf("c"), parsed(JsoncEdit.remove(text, listOf("a\"b"))!!).keys)
        assertNull(JsoncEdit.remove(text, listOf("zzz")))
        assertNull(JsoncEdit.remove(text, listOf("c", "deeper")))
        assertNull(JsoncEdit.remove("{\"a\": ", listOf("a")))
        assertNull(JsoncEdit.remove("[1]", listOf("a")))
    }

    @Test
    fun `a trailing comma followed by a comment is still stripped`() {
        val stripped = Jsonc.strip("{\n  \"a\": 1, // last\n  /* x */\n}")
        assertEquals(setOf("a"), (Json.parseToJsonElement(stripped) as JsonObject).keys)
    }
}
