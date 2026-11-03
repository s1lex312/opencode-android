package ai.opencode.android.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StaleProcessesTest {

    private val pkg = "ai.opencode.android.debug"
    private fun proc(pid: Int, ppid: Int, cmd: String, uid: Int = 10234) = StaleProcesses.Proc(pid, ppid, uid, cmd)

    @Test
    fun `status parsing`() {
        val status = "Name:\topencode\nState:\tS (sleeping)\nPid:\t412\nPPid:\t1\nUid:\t10234\t10234\t10234\t10234\nGid:\t10234\n"
        val parsed = StaleProcesses.parseStatus(412, status, "opencode\u0000serve\u0000--port\u00004096\u0000")
        assertEquals(proc(412, 1, "opencode serve --port 4096"), parsed)
        assertNull(StaleProcesses.parseStatus(1, "Name:\tx\n", ""))
    }

    @Test
    fun `orphans of our uid are victims, the app and foreign processes are not`() {
        val all = listOf(
            proc(100, 50, pkg),
            proc(101, 50, "$pkg:remote"),
            proc(200, 100, "/data/app/x/lib/arm64/libproot.so -0 --kill-on-exit -r /rootfs"),
            proc(201, 200, "/bin/sh -lc opencode serve"),
            proc(202, 201, "opencode serve --hostname 127.0.0.1 --port 4096"),
            proc(300, 1, "opencode serve --hostname 127.0.0.1 --port 4096"),
            proc(400, 1, "com.termux", uid = 10300),
            proc(500, 1, ""),
        )
        val victims = StaleProcesses.victims(all, selfPid = 100, selfUid = 10234, packageName = pkg)
        assertEquals(listOf(200, 201, 202, 300), victims.map { it.pid })
        assertTrue(victims.first().isProot)
        assertFalse(victims.last().isProot)

        // The live server tree is kept; only the orphan goes.
        val kept = StaleProcesses.victims(all, selfPid = 100, selfUid = 10234, packageName = pkg, keep = setOf(200))
        assertEquals(listOf(300), kept.map { it.pid })
    }

    @Test
    fun `scan reads a proc-like directory`() {
        val dir = Files.createTempDirectory("proc").toFile()
        try {
            java.io.File(dir, "42").apply {
                mkdirs()
                resolve("status").writeText("PPid:\t1\nUid:\t7\t7\t7\t7\n")
                resolve("cmdline").writeText("proot\u0000-0\u0000")
            }
            java.io.File(dir, "self").mkdirs()
            java.io.File(dir, "43").mkdirs()
            val scanned = StaleProcesses.scan(dir)
            assertEquals(listOf(proc(42, 1, "proot -0", uid = 7)), scanned)
            assertTrue(scanned.single().isProot)
        } finally {
            dir.deleteRecursively()
        }
    }
}
