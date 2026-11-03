package ai.opencode.android.server

import java.io.File

/**
 * Finds and kills server processes left behind by an earlier run.
 *
 * proot ignores SIGTERM, and when it dies from SIGKILL its tracees are detached instead of killed:
 * the orphaned `opencode serve` keeps the port, and every new server exits with `ServeError` while
 * the health check happily talks to the orphan. The only processes this app ever forks are proot
 * and its guests, so every process of our UID other than the app itself is fair game.
 */
internal object StaleProcesses {

    data class Proc(val pid: Int, val ppid: Int, val uid: Int, val cmdline: String) {
        val isProot: Boolean
            get() = cmdline.substringBefore(' ').let { it.endsWith("libproot.so") || it.endsWith("/proot") || it == "proot" }
    }

    /** Parses `/proc/<pid>/status` (PPid and real Uid). */
    fun parseStatus(pid: Int, status: String, cmdline: String): Proc? {
        var ppid: Int? = null
        var uid: Int? = null
        status.lineSequence().forEach { line ->
            when {
                line.startsWith("PPid:") -> ppid = line.substringAfter(':').trim().toIntOrNull()
                line.startsWith("Uid:") -> uid = line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
            }
        }
        return Proc(pid, ppid ?: return null, uid ?: return null, cmdline.replace('\u0000', ' ').trim())
    }

    fun scan(procDir: File = File("/proc")): List<Proc> =
        procDir.listFiles().orEmpty().mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            runCatching {
                parseStatus(pid, File(dir, "status").readText(), File(dir, "cmdline").readText())
            }.getOrNull()
        }

    /**
     * Processes to kill: our UID, not the app itself (nor another process of the app), not part of
     * the tree rooted at any pid in [keep].
     */
    fun victims(all: List<Proc>, selfPid: Int, selfUid: Int, packageName: String, keep: Set<Int> = emptySet()): List<Proc> {
        val children = all.groupBy { it.ppid }
        val protected = HashSet<Int>()
        val queue = ArrayDeque(keep)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            if (!protected.add(pid)) continue
            children[pid].orEmpty().forEach { queue.addLast(it.pid) }
        }
        return all.filter {
            it.uid == selfUid &&
                it.pid != selfPid &&
                it.pid !in protected &&
                !it.cmdline.startsWith(packageName) &&
                it.cmdline.isNotEmpty()
        }
    }
}
