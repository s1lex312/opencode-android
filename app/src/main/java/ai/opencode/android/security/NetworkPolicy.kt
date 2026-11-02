package ai.opencode.android.security

import java.net.URI
import java.net.URISyntaxException

/**
 * Decides whether a user-entered server address may be used.
 *
 * Rules (enforced both when saving settings and again by an OkHttp interceptor, so a cached
 * configuration can never bypass them):
 *
 *  * `https://` to any host is allowed;
 *  * `http://` to loopback is always allowed (an opencode server running on this very device);
 *  * `http://` to RFC1918 / link-local / CGNAT / `.local` hosts is allowed only when the user
 *    explicitly enables "allow cleartext on local network" in settings;
 *  * `http://` to any other host is always rejected.
 */
object NetworkPolicy {

    const val VERSION = 1

    sealed interface Result {
        data class Allowed(val url: String, val host: String, val port: Int, val secure: Boolean) : Result
        data class Rejected(val reason: String) : Result
    }

    private val LOOPBACK_NAMES = setOf("localhost", "localhost.localdomain", "ip6-localhost")

    /**
     * Normalises user input into a canonical base URL.
     *
     * Accepted forms: `host`, `host:port`, `https://host:port/base/path`,
     * `http://10.0.2.2:4096`. A missing scheme is inferred: loopback and private hosts default
     * to `http`, everything else to `https`.
     */
    fun normalize(rawInput: String, allowPrivateNetworkCleartext: Boolean): Result {
        var raw = rawInput.trim()
        if (raw.isEmpty()) return Result.Rejected("Server address is empty")

        if (!raw.contains("://")) {
            val hostPart = raw.substringBefore('/').substringBefore(':')
            val inferredScheme =
                if (isLoopbackHost(hostPart) || isPrivateHost(hostPart)) "http" else "https"
            raw = "$inferredScheme://$raw"
        }

        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            return Result.Rejected("Server address is not a valid URL")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Rejected("Only http:// or https:// addresses are supported")
        }

        val host = uri.host ?: return Result.Rejected("Server address has no host")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 4096
        if (port !in 1..65535) return Result.Rejected("Port must be between 1 and 65535")

        val secure = scheme == "https"
        if (!secure) {
            if (isLoopbackHost(host)) {
                // always fine
            } else if (isPrivateHost(host)) {
                if (!allowPrivateNetworkCleartext) {
                    return Result.Rejected(
                        "Cleartext HTTP to a local-network host is disabled. Enable it in settings or use https://",
                    )
                }
            } else {
                return Result.Rejected("Cleartext HTTP is only allowed for localhost or local-network hosts")
            }
        }

        val path = uri.rawPath.orEmpty().trimEnd('/')
        val hostForUrl = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        val defaultPort = if (secure) 443 else 80
        val portPart = if (port == defaultPort) "" else ":$port"
        val url = "$scheme://$hostForUrl$portPart$path"

        return Result.Allowed(url = url, host = host, port = port, secure = secure)
    }

    fun isLoopbackHost(host: String): Boolean {
        val cleaned = host.lowercase().trim('[', ']')
        if (cleaned in LOOPBACK_NAMES) return true
        if (cleaned == "::1") return true
        return cleaned.startsWith("127.")
    }

    /** RFC1918, link-local, CGNAT and IPv6 ULA/link-local ranges plus mDNS `.local` names. */
    fun isPrivateHost(host: String): Boolean {
        val cleaned = host.lowercase().trim('[', ']')
        if (isLoopbackHost(cleaned)) return true
        if (cleaned.endsWith(".local") || cleaned.endsWith(".internal") || cleaned.endsWith(".lan")) return true
        if (cleaned.contains(':')) {
            // IPv6 unique-local (fc00::/7) and link-local (fe80::/10).
            return cleaned.startsWith("fc") || cleaned.startsWith("fd") || cleaned.startsWith("fe8") ||
                cleaned.startsWith("fe9") || cleaned.startsWith("fea") || cleaned.startsWith("feb")
        }
        val octets = cleaned.split('.')
        if (octets.size != 4) return false
        val parts = octets.map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 0..255 }) return false
        val (a, b) = parts[0] to parts[1]
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true // link-local
            a == 100 && b in 64..127 -> true // CGNAT
            else -> false
        }
    }

    /** Android emulator host loopback alias; treated as "this device" for policy purposes. */
    fun isEmulatorHostAlias(host: String): Boolean = host.trim('[', ']') == "10.0.2.2"
}
