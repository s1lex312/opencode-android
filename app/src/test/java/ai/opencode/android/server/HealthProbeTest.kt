package ai.opencode.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthProbeTest {

    private class FakePinger(private val answers: Map<String, Int?>) : HttpPinger {
        val calls = mutableListOf<String>()

        override fun status(path: String, timeoutMs: Int): Int? {
            calls += path
            return answers[path]
        }
    }

    @Test
    fun `root endpoint is the primary probe`() {
        val pinger = FakePinger(mapOf("/" to 200, "/api/info" to 200))
        val result = HealthProbe(pinger).check()

        assertTrue(result.healthy)
        assertEquals("/", result.path)
        assertEquals(200, result.code)
        assertEquals(listOf("/"), pinger.calls)
    }

    @Test
    fun `falls back to api endpoints`() {
        val pinger = FakePinger(mapOf("/" to null, "/api/info" to 401))
        val result = HealthProbe(pinger).check()

        assertTrue(result.healthy)
        assertEquals("/api/info", result.path)
        assertEquals(401, result.code)
        assertEquals(listOf("/", "/api/info"), pinger.calls)
    }

    @Test
    fun `5xx answers are not healthy`() {
        val pinger = FakePinger(mapOf("/" to 503, "/api/info" to 500, "/config" to null))
        val result = HealthProbe(pinger).check()

        assertFalse(result.healthy)
        assertTrue(result.reason!!.contains("HTTP"))
    }

    @Test
    fun `no answer means down`() {
        val pinger = FakePinger(emptyMap())
        val result = HealthProbe(pinger).check()

        assertFalse(result.healthy)
        assertTrue(result.reason!!.contains("No answer"))
        assertEquals(0, result.code)
    }
}
