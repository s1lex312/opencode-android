package ai.opencode.android.bridge

import ai.opencode.android.data.remote.OpencodeJson
import ai.opencode.android.data.remote.arr
import ai.opencode.android.data.remote.obj
import ai.opencode.android.data.remote.objects
import ai.opencode.android.data.remote.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class AndroidToolsServerTest {

    private val fake = object : ToolProvider {
        override fun definitions(): JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("name", "echo")
                    put("description", "echo")
                    put("inputSchema", buildJsonObject { put("type", "object") })
                },
            )
        }

        override fun call(name: String, arguments: JsonObject): ToolOutcome =
            if (name == "echo") ToolOutcome(arguments.str("text").orEmpty()) else ToolOutcome("nope", isError = true)
    }

    private val server = AndroidToolsServer(fake) { "secret" }

    private fun post(body: String, auth: String? = "Bearer secret", method: String = "POST", path: String = "/mcp") =
        server.route(
            AndroidToolsServer.Request(method, path, listOfNotNull(auth?.let { "authorization" to it }).toMap(), body.toByteArray()),
        )

    @Test
    fun `rejects requests without the token`() {
        assertEquals(401, post("{}", auth = null).status)
        assertEquals(401, post("{}", auth = "Bearer wrong").status)
    }

    @Test
    fun `only POST on the mcp path`() {
        assertEquals(405, post("", method = "GET").status)
        assertEquals(404, post("{}", path = "/other").status)
    }

    @Test
    fun `initialize, notifications and tools`() {
        val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        assertEquals(200, init.status)
        val result = OpencodeJson.parseToJsonElement(init.body) as JsonObject
        assertEquals("2025-06-18", result.str("result", "protocolVersion"))
        assertEquals("android", result.str("result", "serverInfo", "name"))

        assertEquals(202, post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").status)

        val list = OpencodeJson.parseToJsonElement(post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").body) as JsonObject
        assertEquals("echo", list.obj("result")?.arr("tools")?.objects()?.single()?.str("name"))

        val call = OpencodeJson.parseToJsonElement(
            post("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""").body,
        ) as JsonObject
        assertEquals("hi", call.obj("result")?.arr("content")?.objects()?.single()?.str("text"))
        assertNull(call.str("result", "isError"))

        val failed = OpencodeJson.parseToJsonElement(
            post("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"missing"}}""").body,
        ) as JsonObject
        assertEquals("true", failed.str("result", "isError"))
    }

    @Test
    fun `unknown method and garbage`() {
        val unknown = OpencodeJson.parseToJsonElement(post("""{"jsonrpc":"2.0","id":9,"method":"resources/list"}""").body) as JsonObject
        assertEquals("-32601", unknown.str("error", "code"))
        assertEquals(400, post("not json").status)
    }

    @Test
    fun `batches answer every request`() {
        val response = post(
            """[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":2,"method":"ping"}]""",
        )
        assertEquals(2, (OpencodeJson.parseToJsonElement(response.body) as JsonArray).size)
    }

    @Test
    fun `parses a raw HTTP request`() {
        val raw = "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\nContent-Length: 2\r\n\r\n{}"
        val request = AndroidToolsServer.readRequest(ByteArrayInputStream(raw.toByteArray()))!!
        assertEquals("POST", request.method)
        assertEquals("/mcp", request.path)
        assertEquals("Bearer secret", request.headers["authorization"])
        assertEquals("{}", String(request.body))
    }

    @Test
    fun `serves over a real socket`() {
        val real = AndroidToolsServer(fake) { "secret" }
        val port = real.start(41_800)
        try {
            val connection = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer secret")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray()) }
            assertEquals(200, connection.responseCode)
            assertTrue(connection.inputStream.bufferedReader().readText().contains("\"result\""))
        } finally {
            real.stop()
        }
    }
}
