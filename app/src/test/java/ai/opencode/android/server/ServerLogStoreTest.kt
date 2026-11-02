package ai.opencode.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerLogStoreTest {

    private fun tempFile(): File =
        File(System.getProperty("java.io.tmpdir"), "logstore-" + System.nanoTime()).resolve("server.log").apply {
            parentFile?.mkdirs()
        }

    @Test
    fun `appends and tails lines`() {
        val store = ServerLogStore(tempFile())
        (1..50).forEach { store.append("line $it") }

        assertEquals(listOf("line 49", "line 50"), store.tail(2))
        assertEquals(50, store.tail(500).size)
        assertTrue(store.sizeBytes() > 0)
    }

    @Test
    fun `rotates when the file grows too large`() {
        val file = tempFile()
        val store = ServerLogStore(file, maxBytes = 2_000, keepTailBytes = 500)
        repeat(500) { store.append("payload line number $it " + "x".repeat(40)) }
        val sizeAfterManyWrites = store.sizeBytes()

        store.append("after rotation")

        assertTrue("log should stay bounded, was $sizeAfterManyWrites", sizeAfterManyWrites < 20_000)
        assertTrue(store.tail(10).last().contains("after rotation"))
    }

    @Test
    fun `clear removes the file`() {
        val store = ServerLogStore(tempFile())
        store.append("something")
        store.clear()
        assertEquals(0, store.sizeBytes())
        assertTrue(store.tail().isEmpty())
    }

    @Test
    fun `tail of a missing file is empty`() {
        val store = ServerLogStore(File(tempFile().parentFile, "missing.log"))
        assertTrue(store.tail(10).isEmpty())
    }
}
