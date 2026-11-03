package ai.opencode.android.server

import ai.opencode.android.ui.tools.EnvPackage
import ai.opencode.android.ui.tools.EnvScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestEnvironmentTest {

    @Test
    fun `android variables never reach the guest, ordinary ones do`() {
        val host = listOf(
            "PATH", "HOME", "OPENCODE_PASSWORD", "HTTPS_PROXY",
            "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_ART_ROOT", "ANDROID_SOCKET_zygote",
            "BOOTCLASSPATH", "LD_LIBRARY_PATH", "PROOT_TMP_DIR",
        )
        val args = GuestEnvironment.unsetArgs(host)
        val unset = args.chunked(2).map { (flag, name) -> assertEquals("-u", flag); name }.toSet()
        assertEquals(
            setOf("ANDROID_DATA", "ANDROID_ROOT", "ANDROID_ART_ROOT", "ANDROID_SOCKET_zygote", "BOOTCLASSPATH", "LD_LIBRARY_PATH", "PROOT_TMP_DIR"),
            unset,
        )
        listOf("PATH", "HOME", "OPENCODE_PASSWORD", "HTTPS_PROXY").forEach { assertFalse(it, it in unset) }
    }

    @Test
    fun `the pair platformdirs reads is unset even when the app process lacks it`() {
        val unset = GuestEnvironment.unsetArgs(emptyList()).filter { it != "-u" }
        assertEquals(listOf("ANDROID_DATA", "ANDROID_ROOT"), unset)
    }

    @Test
    fun `the installer clears them before pip runs`() {
        val script = EnvScripts.install(setOf(EnvPackage.PYTHON))
        val unsetAt = script.indexOf("unset ANDROID_DATA ANDROID_ROOT")
        assertTrue(unsetAt >= 0)
        assertTrue(unsetAt < script.indexOf("pip install"))
    }
}
