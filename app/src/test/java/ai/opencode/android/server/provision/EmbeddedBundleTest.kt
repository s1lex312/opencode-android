package ai.opencode.android.server.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Setup screen must never claim "Lite build" for a Full APK: the flavour is a build-time fact,
 * the asset probe is a runtime fact, and the two are evaluated separately.
 */
class EmbeddedBundleTest {

    private fun state(
        flavor: String = "full",
        declared: Boolean = true,
        present: Boolean = true,
        size: Long = 105_575_703L,
    ) = EmbeddedBundle.evaluate(
        EmbeddedBundleInputs(
            flavor = flavor,
            declaredEmbedded = declared,
            assetPresent = present,
            assetSizeBytes = size,
        ),
    )

    @Test
    fun `full build with readable asset is embedded`() {
        val result = state()
        assertEquals(EmbeddedBundleStatus.EMBEDDED, result.status)
        assertTrue(result.isEmbedded)
        assertEquals(105_575_703L, result.assetSizeBytes)
        assertEquals("full", result.flavor)
        assertTrue(result.label.contains("embedded"))
    }

    @Test
    fun `full build without the asset is broken, never lite`() {
        val result = state(present = false, size = 0)
        assertEquals(EmbeddedBundleStatus.BROKEN_FULL, result.status)
        assertTrue(result.isBrokenFull)
        assertFalse(result.isLite)
        assertTrue(result.label.contains("MISSING"))
    }

    @Test
    fun `lite build without asset reports lite`() {
        val result = state(flavor = "lite", declared = false, present = false, size = 0)
        assertEquals(EmbeddedBundleStatus.LITE, result.status)
        assertTrue(result.isLite)
        assertTrue(result.label.contains("Lite build"))
    }

    @Test
    fun `bundle present in a lite build is still usable`() {
        // Local experiments may drop a bundle into the assets of a non-Full build; do not hide it.
        val result = state(flavor = "lite", declared = false)
        assertEquals(EmbeddedBundleStatus.EMBEDDED, result.status)
    }

    @Test
    fun `truncated tar asset is found`() {
        // Exactly what CI produced: aapt2 stored the bundle as `local-env.tar` (no ".gz").
        assertEquals(
            "local-env.tar",
            EmbeddedBundle.findBundle(listOf("local-env.json", "local-env.tar")),
        )
    }

    @Test
    fun `exact name wins over the truncated one`() {
        assertEquals(
            "local-env.tar.gz",
            EmbeddedBundle.findBundle(listOf("local-env.json", "local-env.tar", "local-env.tar.gz")),
        )
    }

    @Test
    fun `the json manifest is never mistaken for the bundle`() {
        assertNull(EmbeddedBundle.findBundle(listOf("local-env.json")))
        assertNull(EmbeddedBundle.findBundle(emptyList()))
    }

    @Test
    fun `probe without a known size still counts as present`() {
        // Compressed assets only expose a stream; a 0 size must not turn a Full build into Lite.
        assertEquals(EmbeddedBundleStatus.EMBEDDED, state(size = 0).status)
    }
}
