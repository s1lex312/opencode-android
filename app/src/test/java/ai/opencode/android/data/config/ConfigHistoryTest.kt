package ai.opencode.android.data.config

import ai.opencode.android.server.LocalConfigFile
import ai.opencode.android.server.provision.EnvPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigHistoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_000_000_000_000L
    private fun history(max: Int = 3) = ConfigHistory(File(temp.root, "history"), max) { now }

    @Test
    fun `versions are newest first, duplicates of the newest are skipped, old ones pruned`() {
        val history = history(max = 3)
        assertTrue(history.keep("{\"a\":1}", ConfigHistory.Source.EDITOR))
        assertFalse(history.keep("{\"a\":1}", ConfigHistory.Source.EXTERNAL))
        listOf(2, 3, 4).forEach {
            now += 1_000
            assertTrue(history.keep("{\"a\":$it}", ConfigHistory.Source.APP))
        }
        val versions = history.versions()
        assertEquals(listOf("{\"a\":4}", "{\"a\":3}", "{\"a\":2}"), versions.map(history::read))
        assertEquals(ConfigHistory.Source.APP, versions.first().source)
        assertFalse(history.keep("  ", ConfigHistory.Source.APP))
    }

    @Test
    fun `two changes in the same millisecond keep their order`() {
        val history = history(max = 10)
        history.keep("first", ConfigHistory.Source.APP)
        history.keep("second", ConfigHistory.Source.EXTERNAL)
        assertEquals(listOf("second", "first"), history.versions().map(history::read))
    }

    @Test
    fun `versions saved by 1_7_0 are still listed`() {
        val dir = File(temp.root, "history").apply { mkdirs() }
        File(dir, "opencode-20261103-141233.json").writeText("{}")
        val version = history().versions().single()
        assertEquals(ConfigHistory.Source.EDITOR, version.source)
    }

    @Test
    fun `the app keeps the text it is about to change`() = runBlocking {
        val history = history(max = 10)
        val config = File(temp.root, "rootfs/root/.config/opencode/opencode.jsonc").apply { parentFile!!.mkdirs() }
        val original = "{\n  // mine\n  \"mcp\": { \"a\": {}, \"b\": {} }\n}"
        config.writeText(original)
        assertTrue(LocalConfigFile(EnvPaths(temp.root), history).remove(listOf("mcp", "a")))
        assertEquals(listOf(original), history.versions().map(history::read))
        assertEquals(ConfigHistory.Source.APP, history.versions().single().source)
    }
}
