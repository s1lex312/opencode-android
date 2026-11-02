package ai.opencode.android.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class FormattersTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `relative time for recent values`() {
        assertEquals("now", formatRelativeTime(now - 5_000, now))
        assertEquals("5m ago", formatRelativeTime(now - TimeUnit.MINUTES.toMillis(5), now))
        assertEquals("3h ago", formatRelativeTime(now - TimeUnit.HOURS.toMillis(3), now))
        assertEquals("2d ago", formatRelativeTime(now - TimeUnit.DAYS.toMillis(2), now))
        assertEquals("", formatRelativeTime(0, now))
    }

    @Test
    fun `older timestamps fall back to a date`() {
        val old = now - TimeUnit.DAYS.toMillis(30)
        val formatted = formatRelativeTime(old, now)
        assertEquals(false, formatted.endsWith("ago"))
    }

    @Test
    fun `ellipsize and one-line helpers`() {
        assertEquals("abc", "abc".ellipsize(10))
        assertEquals("ab…", "abcdef".ellipsize(3))
        assertEquals("a b c", "a\n b\tc".oneLine())
    }
}
