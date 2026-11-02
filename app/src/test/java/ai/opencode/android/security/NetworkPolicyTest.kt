package ai.opencode.android.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    @Test
    fun `https is accepted for any host`() {
        val result = NetworkPolicy.normalize("https://opencode.example.com:8443/base", allowPrivateNetworkCleartext = false)

        assertTrue(result is NetworkPolicy.Result.Allowed)
        result as NetworkPolicy.Result.Allowed
        assertEquals("https://opencode.example.com:8443/base", result.url)
        assertTrue(result.secure)
    }

    @Test
    fun `loopback http is always allowed`() {
        val result = NetworkPolicy.normalize("http://127.0.0.1:4096", allowPrivateNetworkCleartext = false)

        assertTrue(result is NetworkPolicy.Result.Allowed)
        assertEquals("http://127.0.0.1:4096", (result as NetworkPolicy.Result.Allowed).url)
    }

    @Test
    fun `localhost http is allowed without opt-in`() {
        val result = NetworkPolicy.normalize("http://localhost:4096", allowPrivateNetworkCleartext = false)
        assertTrue(result is NetworkPolicy.Result.Allowed)
    }

    @Test
    fun `private network http needs explicit opt-in`() {
        val blocked = NetworkPolicy.normalize("http://192.168.1.20:4096", allowPrivateNetworkCleartext = false)
        assertTrue(blocked is NetworkPolicy.Result.Rejected)

        val allowed = NetworkPolicy.normalize("http://192.168.1.20:4096", allowPrivateNetworkCleartext = true)
        assertTrue(allowed is NetworkPolicy.Result.Allowed)
        assertEquals("http://192.168.1.20:4096", (allowed as NetworkPolicy.Result.Allowed).url)
    }

    @Test
    fun `public cleartext is rejected even when private cleartext is enabled`() {
        val result = NetworkPolicy.normalize("http://example.com:4096", allowPrivateNetworkCleartext = true)
        assertTrue(result is NetworkPolicy.Result.Rejected)
    }

    @Test
    fun `missing scheme defaults to http for private hosts and https for public hosts`() {
        val private = NetworkPolicy.normalize("10.0.2.2:4096", allowPrivateNetworkCleartext = true)
        assertEquals("http://10.0.2.2:4096", (private as NetworkPolicy.Result.Allowed).url)

        val public = NetworkPolicy.normalize("code.example.com", allowPrivateNetworkCleartext = true)
        assertEquals("https://code.example.com", (public as NetworkPolicy.Result.Allowed).url)
    }

    @Test
    fun `missing port defaults to opencode port for http`() {
        val result = NetworkPolicy.normalize("http://192.168.0.5", allowPrivateNetworkCleartext = true)
        assertEquals("http://192.168.0.5:4096", (result as NetworkPolicy.Result.Allowed).url)
    }

    @Test
    fun `rejects unsupported schemes and empty input`() {
        assertTrue(NetworkPolicy.normalize("ftp://example.com", true) is NetworkPolicy.Result.Rejected)
        assertTrue(NetworkPolicy.normalize("   ", true) is NetworkPolicy.Result.Rejected)
    }

    @Test
    fun `classifies private ranges`() {
        assertTrue(NetworkPolicy.isPrivateHost("172.16.5.4"))
        assertTrue(NetworkPolicy.isPrivateHost("172.31.255.1"))
        assertTrue(NetworkPolicy.isPrivateHost("192.168.0.1"))
        assertTrue(NetworkPolicy.isPrivateHost("10.1.2.3"))
        assertTrue(NetworkPolicy.isPrivateHost("100.100.1.1"))
        assertTrue(NetworkPolicy.isPrivateHost("169.254.10.10"))
        assertTrue(NetworkPolicy.isPrivateHost("fd00::1"))
        assertTrue(NetworkPolicy.isPrivateHost("laptop.local"))
        assertTrue(!NetworkPolicy.isPrivateHost("172.32.0.1"))
        assertTrue(!NetworkPolicy.isPrivateHost("8.8.8.8"))
    }

    @Test
    fun `loopback detection`() {
        assertTrue(NetworkPolicy.isLoopbackHost("127.0.0.1"))
        assertTrue(NetworkPolicy.isLoopbackHost("127.5.6.7"))
        assertTrue(NetworkPolicy.isLoopbackHost("localhost"))
        assertTrue(NetworkPolicy.isLoopbackHost("::1"))
        assertTrue(!NetworkPolicy.isLoopbackHost("192.168.1.1"))
    }

    @Test
    fun `redaction hides secrets in urls and error previews`() {
        assertEquals(
            "https://h/api/event?auth_token=***&x=1",
            Redaction.url("https://h/api/event?auth_token=c2VjcmV0&x=1"),
        )
        assertEquals("***", Redaction.secret("super-secret"))
        assertEquals("<unset>", Redaction.secret(null))
        assertTrue(Redaction.preview("x".repeat(1000), 50).length <= 51)
    }
}
