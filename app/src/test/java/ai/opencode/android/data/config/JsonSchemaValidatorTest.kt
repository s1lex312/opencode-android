package ai.opencode.android.data.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSchemaValidatorTest {

    private val schema = Json.parseToJsonElement(
        """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "${'$'}schema": {"type": "string"},
            "model": {"type": "string"},
            "share": {"enum": ["manual", "auto", "disabled"]},
            "mcp": {"type": "object", "additionalProperties": {"${'$'}ref": "#/${'$'}defs/Mcp"}},
            "agent": {"type": "object", "additionalProperties": {"type": "object", "properties": {"temperature": {"type": "number", "minimum": 0, "maximum": 2}}}}
          },
          "${'$'}defs": {
            "Mcp": {
              "anyOf": [
                {"type": "object", "required": ["type", "command"], "properties": {"type": {"const": "local"}, "command": {"type": "array", "items": {"type": "string"}}}, "additionalProperties": false},
                {"type": "object", "required": ["type", "url"], "properties": {"type": {"const": "remote"}, "url": {"type": "string"}}, "additionalProperties": false}
              ]
            }
          }
        }
        """.trimIndent(),
    ) as JsonObject

    private fun errors(json: String) = JsonSchemaValidator(schema).validate(Json.parseToJsonElement(json))

    @Test
    fun `a valid config has no errors`() {
        assertEquals(
            emptyList<SchemaError>(),
            errors("""{"${'$'}schema":"x","model":"anthropic/claude","share":"auto","mcp":{"a":{"type":"local","command":["npx","x"]},"b":{"type":"remote","url":"https://x"}}}"""),
        )
    }

    @Test
    fun `unknown keys, wrong types, enums, refs and ranges are reported with their path`() {
        val found = errors("""{"modle":"x","share":"always","mcp":{"a":{"type":"local","command":"npx"}},"agent":{"build":{"temperature":3}}}""")
        val byPath = found.associate { it.path to it.message }
        assertTrue(byPath["modle"]!!.contains("did you mean \"model\""))
        assertTrue(byPath["share"]!!.startsWith("must be one of"))
        assertTrue(byPath.containsKey("mcp.a"))
        assertEquals("must be ≤ 2", byPath["agent.build.temperature"])
    }

    @Test
    fun `jsonc comments and trailing commas are stripped but strings are kept`() {
        val text = """
            {
              // a comment
              "url": "https://example.com/a//b", /* block */
              "list": [1, 2,],
            }
        """.trimIndent()
        val parsed = Json.parseToJsonElement(Jsonc.strip(text)) as JsonObject
        assertEquals("\"https://example.com/a//b\"", parsed["url"].toString())
        assertEquals("[1,2]", parsed["list"].toString())
    }
}
