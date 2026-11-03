package ai.opencode.android.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReadCoalescerTest {

    private var now = 0L
    private val coalescer = ReadCoalescer(reuseMillis = 1_000, clock = { now })
    private val calls = AtomicInteger()

    private fun answer(value: Int): () -> JsonElement? = {
        calls.incrementAndGet()
        JsonPrimitive(value)
    }

    @Test
    fun `callers arriving while a read is in flight share it`() = runBlocking {
        val gate = CountDownLatch(1)
        val slow: () -> JsonElement? = {
            calls.incrementAndGet()
            gate.await(5, TimeUnit.SECONDS)
            JsonPrimitive(1)
        }
        val first = async { coalescer.get("/session", reusable = false, load = slow) }
        while (calls.get() == 0) yield()
        val second = async { coalescer.get("/session", reusable = false, load = answer(2)) }
        val third = async { coalescer.get("/session", reusable = false, load = answer(3)) }
        yield()
        gate.countDown()
        assertEquals(listOf(JsonPrimitive(1), JsonPrimitive(1), JsonPrimitive(1)), listOf(first.await(), second.await(), third.await()))
        assertEquals(1, calls.get())
        assertEquals(2L, coalescer.shared)
    }

    @Test
    fun `a change on the server stops sharing, so nobody gets an answer older than the change`() = runBlocking {
        val gate = CountDownLatch(1)
        val first = async {
            coalescer.get("/session", reusable = true) {
                calls.incrementAndGet()
                gate.await(5, TimeUnit.SECONDS)
                JsonPrimitive(1)
            }
        }
        while (calls.get() == 0) yield()
        coalescer.invalidate()
        val second = coalescer.get("/session", reusable = true, load = answer(2))
        gate.countDown()
        assertEquals(JsonPrimitive(1), first.await())
        assertEquals(JsonPrimitive(2), second)
        assertEquals(2, calls.get())
    }

    @Test
    fun `a finished event-backed read is reused briefly, other reads are not`() = runBlocking {
        assertEquals(JsonPrimitive(1), coalescer.get("/session", reusable = true, load = answer(1)))
        now += 500
        assertEquals(JsonPrimitive(1), coalescer.get("/session", reusable = true, load = answer(2)))
        now += 600
        assertEquals(JsonPrimitive(3), coalescer.get("/session", reusable = true, load = answer(3)))

        assertEquals(JsonPrimitive(4), coalescer.get("/file", reusable = false, load = answer(4)))
        assertEquals(JsonPrimitive(5), coalescer.get("/file", reusable = false, load = answer(5)))
        assertEquals(4, calls.get())
    }

    @Test
    fun `a failed read is not handed to the next caller`() = runBlocking {
        try {
            coalescer.get("/session", reusable = true) { throw ApiException(500, "boom") }
            fail("expected the failure")
        } catch (expected: ApiException) {
            assertEquals("boom", expected.message)
        }
        assertEquals(JsonPrimitive(7), coalescer.get("/session", reusable = true, load = answer(7)))
    }

    @Test
    fun `a caller that leaves does not cancel the read the others wait for`() = runBlocking {
        val gate = CountDownLatch(1)
        val started = CompletableDeferred<Unit>()
        val leaving = async {
            coalescer.get("/session", reusable = false) {
                started.complete(Unit)
                gate.await(5, TimeUnit.SECONDS)
                JsonPrimitive(9)
            }
        }
        started.await()
        val staying = async { coalescer.get("/session", reusable = false, load = answer(0)) }
        yield()
        leaving.cancel()
        gate.countDown()
        assertEquals(JsonPrimitive(9), withTimeout(5_000) { staying.await() })
        assertTrue(leaving.isCancelled)
    }
}
