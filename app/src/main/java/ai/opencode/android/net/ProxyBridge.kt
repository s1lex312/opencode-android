package ai.opencode.android.net

import ai.opencode.android.settings.ProxyScope
import ai.opencode.android.settings.ProxySettings
import ai.opencode.android.settings.ProxyType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Upstream as the bridge uses it: [ProxySettings] plus the password from the secret store. */
data class ProxyUpstream(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
    val scope: ProxyScope = ProxyScope.LISTED,
    val hosts: List<String> = ProxySettings.DEFAULT_HOSTS,
    val bypass: List<String> = emptyList(),
) {
    val hasCredentials: Boolean get() = username.isNotEmpty()

    /** True when [host] must go through the upstream; local and private addresses never do. */
    fun routes(host: String): Boolean {
        val name = host.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (HostRules.isLocal(name)) return false
        return when (scope) {
            ProxyScope.LISTED -> hosts.any { HostRules.matches(name, it) }
            ProxyScope.ALL -> bypass.none { HostRules.matches(name, it) }
        }
    }

    companion object {
        fun from(settings: ProxySettings, password: String?): ProxyUpstream = ProxyUpstream(
            type = settings.type,
            host = settings.host.trim(),
            port = settings.port,
            username = settings.username,
            password = password.orEmpty(),
            scope = settings.scope,
            hosts = settings.hosts,
            bypass = settings.bypass,
        )
    }
}

object HostRules {

    /**
     * `example.com` matches itself and its subdomains, `*.example.com` only subdomains,
     * `bedrock-runtime.*.amazonaws.com` any single label in place of the star.
     */
    fun matches(host: String, pattern: String): Boolean {
        val p = pattern.trim().trimEnd('.').lowercase()
        if (p.isEmpty()) return false
        if (p == "*") return true
        if (p.startsWith("*.") && !p.substring(2).contains('*')) {
            val suffix = p.substring(1)
            return host.endsWith(suffix) && host.length > suffix.length
        }
        if (p.contains('*')) {
            val regex = p.split('*').joinToString("[^.]+") { Regex.escape(it) }
            return Regex("^$regex$").matches(host)
        }
        return host == p || host.endsWith(".$p")
    }

    fun isLocal(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host == "::1" || host == "0.0.0.0") return true
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || host.count { it == '.' } != 3) return false
        val (a, b) = octets[0] to octets[1]
        return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
    }
}

/**
 * A minimal HTTP proxy on 127.0.0.1 that forwards to the configured upstream.
 *
 * * `CONNECT host:port` (HTTPS, which is all model APIs) becomes a tunnel through the upstream:
 *   an HTTP `CONNECT` or a SOCKS5 `CONNECT` with the host name resolved by the proxy, so DNS
 *   blocking in the user's network does not matter.
 * * Plain `GET http://...` (apt mirrors, some registries) is forwarded the same way.
 * * Hosts outside [ProxyUpstream.routes] connect directly.
 *
 * The upstream is read per connection, so editing the host or password takes effect immediately;
 * only enabling or disabling the proxy needs the server restarted (its environment changes).
 */
class ProxyBridge(private val upstream: () -> ProxyUpstream?) {

    private val counter = AtomicInteger()
    private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "proxy-bridge-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    private var server: ServerSocket? = null

    /** Listening port, 0 while stopped. */
    val port: Int get() = server?.takeIf { !it.isClosed }?.localPort ?: 0

    val running: Boolean get() = port != 0

    @Synchronized
    fun start(preferredPort: Int = 0): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = runCatching { bind(preferredPort) }.getOrElse { bind(0) }
        server = socket
        pool.execute { acceptLoop(socket) }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun bind(port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 64)
        }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use {
            try {
                client.soTimeout = HEADER_TIMEOUT_MS
                val input = client.getInputStream()
                val output = client.getOutputStream()
                val head = readHead(input) ?: return
                val request = Request.parse(head) ?: return reply(output, 400, "malformed request")
                val config = upstream()
                if (request.method.equals("CONNECT", ignoreCase = true)) {
                    val target = open(request.host, request.port, config)
                    output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                    client.soTimeout = 0
                    pipe(client, target, input)
                } else {
                    val viaHttpProxy = config != null && config.type == ProxyType.HTTP && config.routes(request.host)
                    val target = if (viaHttpProxy) {
                        connect(config!!.host, config.port)
                    } else {
                        open(request.host, request.port, config)
                    }
                    val rewritten = if (viaHttpProxy) {
                        request.withHeaders(proxyAuthorization(config!!))
                    } else {
                        request.originForm()
                    }
                    target.getOutputStream().apply {
                        write(rewritten.toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }
                    client.soTimeout = 0
                    pipe(client, target, input)
                }
            } catch (failure: IOException) {
                runCatching { reply(client.getOutputStream(), 502, failure.message ?: failure.javaClass.simpleName) }
            }
        }
    }

    /** A socket to [host]:[port], through the upstream when it routes that host. */
    internal fun open(host: String, port: Int, config: ProxyUpstream?): Socket {
        if (config == null || !config.routes(host)) return connect(host, port)
        return when (config.type) {
            ProxyType.HTTP -> httpConnect(config, host, port)
            ProxyType.SOCKS5 -> socksConnect(config, host, port)
        }
    }

    private fun connect(host: String, port: Int): Socket = Socket().apply {
        connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        tcpNoDelay = true
    }

    private fun httpConnect(config: ProxyUpstream, host: String, port: Int): Socket {
        val socket = connect(config.host, config.port)
        try {
            socket.soTimeout = HEADER_TIMEOUT_MS
            val authority = authority(host, port)
            val head = buildString {
                append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                append("Host: ").append(authority).append("\r\n")
                proxyAuthorization(config).forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val response = readHead(socket.getInputStream()) ?: throw IOException("proxy closed the connection")
            val status = response.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull()
            if (status != 200) {
                throw IOException(
                    when (status) {
                        407 -> "proxy rejected the login or password (407)"
                        null -> "proxy answered something that is not HTTP"
                        else -> "proxy answered $status to CONNECT $authority"
                    },
                )
            }
            socket.soTimeout = 0
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun socksConnect(config: ProxyUpstream, host: String, port: Int): Socket {
        val socket = connect(config.host, config.port)
        try {
            socket.soTimeout = HEADER_TIMEOUT_MS
            Socks5.handshake(socket.getInputStream(), socket.getOutputStream(), host, port, config.username, config.password)
            socket.soTimeout = 0
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun proxyAuthorization(config: ProxyUpstream): List<Pair<String, String>> {
        if (!config.hasCredentials) return emptyList()
        val token = java.util.Base64.getEncoder().encodeToString("${config.username}:${config.password}".toByteArray())
        return listOf("Proxy-Authorization" to "Basic $token")
    }

    /** Copies both directions until either side closes; [clientInput] may hold bytes already buffered. */
    private fun pipe(client: Socket, target: Socket, clientInput: InputStream) {
        target.use {
            val upstreamDone = pool.submit {
                copy(target.getInputStream(), client.getOutputStream())
                runCatching { client.shutdownOutput() }
            }
            copy(clientInput, target.getOutputStream())
            runCatching { target.shutdownOutput() }
            runCatching { upstreamDone.get() }
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
            }
        } catch (_: SocketException) {
        } catch (_: IOException) {
        }
    }

    private fun reply(output: OutputStream, code: Int, message: String) {
        val reason = if (code == 400) "Bad Request" else "Bad Gateway"
        val body = "opencode proxy: $message\n".toByteArray()
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    /** One HTTP request head: request line plus headers, without the body. */
    internal data class Request(
        val method: String,
        val target: String,
        val version: String,
        val headers: List<Pair<String, String>>,
        val host: String,
        val port: Int,
        val path: String,
    ) {
        fun originForm(): String = render(
            "$method $path $version",
            headers.filterNot { it.first.startsWith("Proxy-", ignoreCase = true) },
        )

        fun withHeaders(extra: List<Pair<String, String>>): String = render(
            "$method $target $version",
            headers.filterNot { header -> extra.any { it.first.equals(header.first, ignoreCase = true) } } + extra,
        )

        private fun render(line: String, headers: List<Pair<String, String>>) = buildString {
            append(line).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }

        companion object {
            fun parse(head: String): Request? {
                val lines = head.split("\r\n").filter { it.isNotEmpty() }
                val parts = lines.firstOrNull()?.split(' ') ?: return null
                if (parts.size != 3) return null
                val (method, target, version) = parts
                val headers = lines.drop(1).mapNotNull { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
                }
                if (method.equals("CONNECT", ignoreCase = true)) {
                    val (host, port) = splitAuthority(target, 443) ?: return null
                    return Request(method, target, version, headers, host, port, "")
                }
                val uri = runCatching { URI(target) }.getOrNull() ?: return null
                if (uri.scheme?.lowercase() != "http" || uri.host.isNullOrBlank()) return null
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val port = if (uri.port > 0) uri.port else 80
                return Request(method, target, version, headers, uri.host.removePrefix("[").removeSuffix("]"), port, path)
            }

            fun splitAuthority(authority: String, defaultPort: Int): Pair<String, Int>? {
                if (authority.startsWith("[")) {
                    val end = authority.indexOf(']')
                    if (end < 0) return null
                    val host = authority.substring(1, end)
                    val port = authority.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                    return host to port
                }
                val colon = authority.lastIndexOf(':')
                if (colon < 0) return authority to defaultPort
                val port = authority.substring(colon + 1).toIntOrNull() ?: return null
                return authority.substring(0, colon) to port
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val HEADER_TIMEOUT_MS = 30_000
        private const val MAX_HEAD_BYTES = 64 * 1024

        private fun authority(host: String, port: Int): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

        /** Reads up to the blank line ending a request or response head; null when the peer closed first. */
        internal fun readHead(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            var matched = 0
            while (buffer.size() < MAX_HEAD_BYTES) {
                val byte = input.read()
                if (byte < 0) return null
                buffer.write(byte)
                matched = when {
                    byte == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    byte == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    else -> 0
                }
                if (matched == 4) return buffer.toString(Charsets.ISO_8859_1.name())
            }
            throw IOException("request head larger than $MAX_HEAD_BYTES bytes")
        }
    }
}

/** Client side of RFC 1928 (CONNECT only) with RFC 1929 username/password login. */
object Socks5 {

    fun handshake(input: InputStream, output: OutputStream, host: String, port: Int, username: String, password: String) {
        val login = username.isNotEmpty()
        output.write(if (login) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        output.flush()
        val greeting = readExactly(input, 2)
        if (greeting[0].toInt() != 5) throw IOException("not a SOCKS5 proxy")
        when (greeting[1].toInt() and 0xFF) {
            0 -> Unit
            2 -> {
                if (!login) throw IOException("SOCKS5 proxy wants a login and password")
                val user = username.toByteArray()
                val pass = password.toByteArray()
                if (user.size > 255 || pass.size > 255) throw IOException("SOCKS5 login or password longer than 255 bytes")
                output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                output.flush()
                val status = readExactly(input, 2)
                if (status[1].toInt() != 0) throw IOException("SOCKS5 proxy rejected the login or password")
            }
            0xFF -> throw IOException(if (login) "SOCKS5 proxy accepts none of our login methods" else "SOCKS5 proxy wants a login and password")
            else -> throw IOException("SOCKS5 proxy chose an unsupported login method")
        }
        val name = host.toByteArray()
        if (name.size > 255) throw IOException("host name too long for SOCKS5")
        output.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte()))
        output.flush()
        val reply = readExactly(input, 4)
        val code = reply[1].toInt() and 0xFF
        if (code != 0) throw IOException("SOCKS5 proxy: ${replyMessage(code)} ($host:$port)")
        val addressLength = when (reply[3].toInt()) {
            1 -> 4
            4 -> 16
            3 -> readExactly(input, 1)[0].toInt() and 0xFF
            else -> throw IOException("SOCKS5 proxy sent an unknown address type")
        }
        readExactly(input, addressLength + 2)
    }

    fun replyMessage(code: Int): String = when (code) {
        1 -> "general failure"
        2 -> "connection not allowed by ruleset"
        3 -> "network unreachable"
        4 -> "host unreachable"
        5 -> "connection refused"
        6 -> "TTL expired"
        7 -> "command not supported"
        8 -> "address type not supported"
        else -> "error $code"
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw IOException("SOCKS5 proxy closed the connection")
            offset += read
        }
        return bytes
    }
}
