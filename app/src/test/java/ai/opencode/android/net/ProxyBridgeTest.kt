package ai.opencode.android.net

import ai.opencode.android.settings.ProxyScope
import ai.opencode.android.settings.ProxySettings
import ai.opencode.android.settings.ProxyType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ProxyBridgeTest {

    private val servers = mutableListOf<MockWebServer>()
    private val bridges = mutableListOf<ProxyBridge>()

    @After
    fun tearDown() {
        bridges.forEach { it.stop() }
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun server(): MockWebServer = MockWebServer().also { it.start(); servers += it }

    private fun bridge(upstream: ProxyUpstream?): ProxyBridge = ProxyBridge { upstream }.also { it.start(); bridges += it }

    private fun client(bridge: ProxyBridge) = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", bridge.port)))
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Test
    fun `host patterns cover subdomains, wildcards and single labels`() {
        assertTrue(HostRules.matches("api.openai.com", "openai.com"))
        assertTrue(HostRules.matches("openai.com", "openai.com"))
        assertFalse(HostRules.matches("notopenai.com", "openai.com"))
        assertTrue(HostRules.matches("x.githubcopilot.com", "*.githubcopilot.com"))
        assertFalse(HostRules.matches("githubcopilot.com", "*.githubcopilot.com"))
        assertTrue(HostRules.matches("bedrock-runtime.us-east-1.amazonaws.com", "bedrock-runtime.*.amazonaws.com"))
        assertFalse(HostRules.matches("bedrock-runtime.a.b.amazonaws.com", "bedrock-runtime.*.amazonaws.com"))
    }

    @Test
    fun `local addresses never go through the upstream`() {
        val all = ProxyUpstream(ProxyType.HTTP, "proxy", 3128, scope = ProxyScope.ALL)
        listOf("localhost", "127.0.0.1", "10.0.0.5", "192.168.1.2", "172.20.0.1", "::1").forEach { assertFalse(it, all.routes(it)) }
        assertTrue(all.routes("example.com"))
        assertFalse(all.copy(bypass = listOf("example.com")).routes("www.example.com"))
        val listed = ProxyUpstream(ProxyType.HTTP, "proxy", 3128, hosts = ProxySettings.DEFAULT_HOSTS)
        assertTrue(listed.routes("api.anthropic.com"))
        assertTrue(listed.routes("API.OpenAI.com."))
        assertFalse(listed.routes("registry.npmjs.org"))
    }

    @Test
    fun `request heads parse into absolute and authority forms`() {
        val connect = ProxyBridge.Request.parse("CONNECT api.openai.com:443 HTTP/1.1\r\nHost: api.openai.com:443\r\n\r\n")!!
        assertEquals("api.openai.com" to 443, connect.host to connect.port)
        val get = ProxyBridge.Request.parse("GET http://ports.ubuntu.com/dists/x?y=1 HTTP/1.1\r\nHost: ports.ubuntu.com\r\nProxy-Connection: keep-alive\r\n\r\n")!!
        assertEquals("ports.ubuntu.com" to 80, get.host to get.port)
        assertEquals("/dists/x?y=1", get.path)
        assertTrue(get.originForm().startsWith("GET /dists/x?y=1 HTTP/1.1\r\n"))
        assertFalse(get.originForm().contains("Proxy-Connection"))
        assertNull(ProxyBridge.Request.parse("GET /relative HTTP/1.1\r\n\r\n"))
        assertEquals("::1" to 8080, ProxyBridge.Request.splitAuthority("[::1]:8080", 443))
    }

    @Test
    fun `plain http goes direct when the host is not listed`() {
        val origin = server()
        origin.enqueue(MockResponse().setBody("direct"))
        val bridge = bridge(ProxyUpstream(ProxyType.HTTP, "127.0.0.1", 1, hosts = listOf("api.openai.com")))
        // MockWebServer listens on localhost, which is always direct.
        client(bridge).newCall(Request.Builder().url(origin.url("/x")).build()).execute().use {
            assertEquals("direct", it.body!!.string())
        }
        assertEquals("/x", origin.takeRequest().path)
    }

    @Test
    fun `plain http is forwarded to an http upstream with credentials`() {
        val upstream = server()
        upstream.enqueue(MockResponse().setBody("via proxy"))
        val bridge = bridge(ProxyUpstream(ProxyType.HTTP, "127.0.0.1", upstream.port, "user", "secret", scope = ProxyScope.ALL))
        client(bridge).newCall(Request.Builder().url("http://example.test/path?q=1").build()).execute().use {
            assertEquals("via proxy", it.body!!.string())
        }
        val seen = upstream.takeRequest()
        assertEquals("http://example.test/path?q=1", seen.requestLine.split(' ')[1])
        assertEquals("Basic dXNlcjpzZWNyZXQ=", seen.getHeader("Proxy-Authorization"))
    }

    @Test
    fun `connect tunnels through a socks5 upstream that resolves the name`() {
        val origin = server()
        origin.enqueue(MockResponse().setBody("tunnelled").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END))
        val socks = FakeSocks(origin.port, expectUser = "u", expectPassword = "p")
        val bridge = bridge(ProxyUpstream(ProxyType.SOCKS5, "127.0.0.1", socks.port, "u", "p", scope = ProxyScope.ALL))
        Socket("127.0.0.1", bridge.port).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            out.write("CONNECT api.example.test:443 HTTP/1.1\r\nHost: api.example.test:443\r\n\r\n".toByteArray())
            out.flush()
            val reply = ProxyBridge.readHead(socket.getInputStream())!!
            assertTrue(reply, reply.startsWith("HTTP/1.1 200"))
            out.write("GET /t HTTP/1.1\r\nHost: api.example.test\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            val response = socket.getInputStream().readBytes().decodeToString()
            assertTrue(response, response.endsWith("tunnelled"))
        }
        assertEquals("api.example.test" to 443, socks.requested)
        socks.close()
    }

    @Test
    fun `upstream failures come back as 502 with the reason`() {
        val bridge = bridge(ProxyUpstream(ProxyType.HTTP, "127.0.0.1", closedPort(), scope = ProxyScope.ALL))
        client(bridge).newCall(Request.Builder().url("http://example.test/").build()).execute().use {
            assertEquals(502, it.code)
            assertTrue(it.body!!.string().startsWith("opencode proxy:"))
        }
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    /** SOCKS5 server for one connection: checks the login, records the target, connects to [originPort]. */
    private class FakeSocks(private val originPort: Int, private val expectUser: String, private val expectPassword: String) {
        private val server = ServerSocket(0)
        val port: Int = server.localPort

        @Volatile
        var requested: Pair<String, Int>? = null

        init {
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { client ->
                        val input = DataInputStream(client.getInputStream())
                        val out = client.getOutputStream()
                        input.readByte()
                        val methods = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                        check(2.toByte() in methods)
                        out.write(byteArrayOf(5, 2))
                        input.readByte()
                        val user = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
                        val pass = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
                        val ok = user == expectUser && pass == expectPassword
                        out.write(byteArrayOf(1, if (ok) 0 else 1))
                        if (!ok) return@thread
                        input.readByte(); input.readByte(); input.readByte()
                        check(input.readByte() == 3.toByte())
                        val host = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.decodeToString()
                        val port = input.readUnsignedShort()
                        requested = host to port
                        out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
                        Socket("127.0.0.1", originPort).use { origin ->
                            val upstream = thread(isDaemon = true) {
                                runCatching { origin.getInputStream().copyTo(out) }
                                runCatching { client.shutdownOutput() }
                            }
                            runCatching { client.getInputStream().copyTo(origin.getOutputStream()) }
                            upstream.join(5_000)
                        }
                    }
                }
            }
        }

        fun close() = runCatching { server.close() }
    }
}
