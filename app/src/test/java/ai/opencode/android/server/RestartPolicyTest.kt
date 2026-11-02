package ai.opencode.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartPolicyTest {

    private val policy = RestartPolicy(maxRestarts = 3, baseDelayMs = 1_000, maxDelayMs = 8_000)

    @Test
    fun `backoff grows exponentially and is capped`() {
        assertEquals(1_000, policy.delayFor(1))
        assertEquals(2_000, policy.delayFor(2))
        assertEquals(4_000, policy.delayFor(3))
        assertEquals(8_000, policy.delayFor(4))
        assertEquals(8_000, policy.delayFor(9))
    }

    @Test
    fun `attempts are bounded`() {
        assertTrue(policy.shouldRestart(0))
        assertTrue(policy.shouldRestart(2))
        assertFalse(policy.shouldRestart(3))
        assertFalse(policy.shouldRestart(10))
    }

    @Test
    fun `defaults are sane`() {
        val defaults = RestartPolicy.DEFAULT
        assertEquals(5, defaults.maxRestarts)
        assertTrue(defaults.delayFor(1) >= 500)
        assertTrue(defaults.delayFor(20) <= 30_000)
    }
}
