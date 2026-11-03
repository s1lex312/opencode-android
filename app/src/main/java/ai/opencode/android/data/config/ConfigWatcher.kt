package ai.opencode.android.data.config

import android.os.FileObserver
import java.io.File

/**
 * Watches the in-app server's config directory and files the text a config had before each change
 * into [ConfigHistory]. This also catches writes the app does not make itself: opencode rewrites
 * the file on every API config patch, and the agent or a terminal can edit it.
 *
 * Changes made while the app was not running are noticed on the next [start] by comparing with the
 * last text seen, kept next to the history.
 */
class ConfigWatcher(
    private val configDir: () -> File,
    private val history: ConfigHistory,
    private val stateDir: File,
) {
    private var observer: FileObserver? = null
    private val known = HashMap<String, String?>()

    @Synchronized
    fun start() {
        if (observer != null) return
        val dir = configDir()
        // The directory belongs to the rootfs; without an environment there is nothing to watch yet.
        if (dir.parentFile?.parentFile?.isDirectory != true) return
        dir.mkdirs()
        CANDIDATES.forEach { name ->
            val current = read(File(dir, name))
            val lastSeen = read(seenFile(name))
            if (lastSeen != null && lastSeen != current) history.keep(lastSeen, ConfigHistory.Source.EXTERNAL)
            remember(name, current)
        }
        @Suppress("DEPRECATION")
        observer = object : FileObserver(dir.absolutePath, CLOSE_WRITE or MOVED_TO or DELETE or MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && path in CANDIDATES) changed(dir, path)
            }
        }.also { it.startWatching() }
    }

    @Synchronized
    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    @Synchronized
    private fun changed(dir: File, name: String) {
        val current = read(File(dir, name))
        val previous = known[name]
        if (previous == current) return
        if (previous != null) history.keep(previous, ConfigHistory.Source.EXTERNAL)
        remember(name, current)
    }

    private fun remember(name: String, text: String?) {
        known[name] = text
        val seen = seenFile(name)
        runCatching {
            if (text == null) seen.delete() else seen.apply { parentFile?.mkdirs() }.writeText(text)
        }
    }

    private fun seenFile(name: String) = File(stateDir, "seen-$name")

    private fun read(file: File): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    private companion object {
        val CANDIDATES = setOf("opencode.jsonc", "opencode.json", "config.json")
    }
}
