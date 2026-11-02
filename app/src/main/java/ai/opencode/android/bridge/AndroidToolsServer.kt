package ai.opencode.android.bridge

import ai.opencode.android.data.remote.OpencodeJson
import ai.opencode.android.data.remote.str
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal MCP server (Streamable HTTP transport, JSON responses only) bound to 127.0.0.1.
 *
 * opencode runs in proot on the same device and shares the network namespace, so it reaches this
 * socket as a `remote` MCP server. Every request must carry `Authorization: Bearer <token>`; the
 * token lives in the encrypted secret store and in opencode's config, nowhere else.
 */
/** What the MCP server exposes; [AndroidTools] in the app, fakes in tests. */
interface ToolProvider {
    fun definitions(): JsonArray

    fun call(name: String, arguments: JsonObject): ToolOutcome
}

class AndroidToolsServer(
    private val tools: ToolProvider,
    private val token: () -> String,
) {
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(WORKERS) { runnable ->
        Thread(runnable, "android-mcp").apply { isDaemon = true }
    }

    /** Bound port, or `null` when stopped. */
    @Volatile
    var port: Int? = null
        private set

    /** Binds the first free port from [preferred]; returns it. Idempotent. */
    @Synchronized
    fun start(preferred: Int): Int {
        port?.let { return it }
        var lastError: IOException? = null
        for (candidate in preferred until preferred + PORT_ATTEMPTS) {
            try {
                val server = ServerSocket(candidate, BACKLOG, InetAddress.getByName("127.0.0.1"))
                socket = server
                port = candidate
                running.set(true)
                Thread({ acceptLoop(server) }, "android-mcp-accept").apply { isDaemon = true }.start()
                Log.i(TAG, "listening on 127.0.0.1:$candidate")
                return candidate
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No free port for the Android tools server")
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        port = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (closed: SocketException) {
                break
            } catch (e: IOException) {
                continue
            }
            workers.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            try {
                val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
                val response = route(request)
                val out = socket.getOutputStream()
                out.write(response.encode())
                out.flush()
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.javaClass.simpleName}")
            }
        }
    }

    internal data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    internal data class Response(val status: Int, val reason: String, val body: String = "", val extraHeaders: Map<String, String> = emptyMap()) {
        fun encode(): ByteArray {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
                if (bytes.isNotEmpty()) append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                extraHeaders.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
                append("Connection: close\r\n\r\n")
            }
            return head.toByteArray(Charsets.US_ASCII) + bytes
        }
    }

    internal fun route(request: Request): Response {
        val expected = "Bearer ${token()}"
        if (request.headers["authorization"] != expected) return Response(401, "Unauthorized")
        if (request.path.substringBefore('?') != PATH) return Response(404, "Not Found")
        if (request.method != "POST") return Response(405, "Method Not Allowed", extraHeaders = mapOf("Allow" to "POST"))
        val payload = runCatching { OpencodeJson.parseToJsonElement(request.body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return Response(400, "Bad Request", error(JsonNull, PARSE_ERROR, "Parse error").toString())
        val replies: JsonElement? = when (payload) {
            is JsonArray -> buildJsonArray { payload.forEach { item -> (item as? JsonObject)?.let(::dispatch)?.let { add(it) } } }
                .takeIf { it.isNotEmpty() }
            is JsonObject -> dispatch(payload)
            else -> error(JsonNull, INVALID_REQUEST, "Invalid request")
        }
        // Notifications and responses get 202 with no body.
        return if (replies == null) Response(202, "Accepted") else Response(200, "OK", replies.toString())
    }

    /** One JSON-RPC message; `null` for notifications (no `id`). */
    private fun dispatch(message: JsonObject): JsonObject? {
        val id = message["id"] ?: return null
        val method = message.str("method") ?: return error(id, INVALID_REQUEST, "Missing method")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        return when (method) {
            "initialize" -> result(
                id,
                buildJsonObject {
                    put("protocolVersion", params.str("protocolVersion") ?: PROTOCOL_VERSION)
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
                    put(
                        "serverInfo",
                        buildJsonObject {
                            put("name", "android")
                            put("version", SERVER_VERSION)
                        },
                    )
                    put("instructions", INSTRUCTIONS)
                },
            )

            "ping" -> result(id, JsonObject(emptyMap()))

            "tools/list" -> result(id, buildJsonObject { put("tools", tools.definitions()) })

            "tools/call" -> {
                val name = params.str("name") ?: return error(id, INVALID_PARAMS, "Missing tool name")
                val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val outcome = tools.call(name, arguments)
                result(
                    id,
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", outcome.text)
                                    },
                                )
                            },
                        )
                        if (outcome.isError) put("isError", true)
                    },
                )
            }

            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun result(id: JsonElement, value: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", value)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

    companion object {
        const val PATH = "/mcp"
        const val DEFAULT_PORT = 4180
        private const val PORT_ATTEMPTS = 20
        private const val BACKLOG = 16
        private const val WORKERS = 4
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_BODY = 4 * 1024 * 1024
        private const val TAG = "AndroidMcp"
        private const val PROTOCOL_VERSION = "2025-03-26"
        private const val SERVER_VERSION = "1.0.0"
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602

        private const val INSTRUCTIONS =
            "Tools that act on the Android phone running this opencode instance: device and battery " +
                "state, clipboard, notifications, opening links and apps, torch, volume, vibration and " +
                "text-to-speech. Shared storage is mounted at /sdcard when the user granted file access."

        /** HTTP/1.1 request head plus a `Content-Length` body (chunked bodies are not used by MCP clients here). */
        internal fun readRequest(input: InputStream): Request? {
            val head = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0) return null
                head.write(b)
                matched = when {
                    (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                    (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
                if (head.size() > MAX_HEAD) return null
            }
            val lines = head.toString(Charsets.US_ASCII.name()).split("\r\n").filter { it.isNotEmpty() }
            val requestLine = lines.firstOrNull()?.split(' ') ?: return null
            if (requestLine.size < 2) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            return Request(requestLine[0].uppercase(), requestLine[1], headers, body.copyOf(read))
        }

        private const val MAX_HEAD = 16 * 1024
    }
}
