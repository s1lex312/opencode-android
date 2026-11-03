package ai.opencode.android.server

import ai.opencode.android.data.config.JsoncEdit
import ai.opencode.android.server.provision.EnvPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Direct access to the in-app server's global config (`/root/.config/opencode/` inside the rootfs).
 * opencode's HTTP API only deep-merges patches, so removing a key (an MCP server, an agent
 * override, a custom provider) is only possible by editing the files; the caller then makes the
 * server reload them.
 */
class LocalConfigFile(private val paths: EnvPaths) {

    private val dir: File get() = File(paths.rootfsDir, "root/.config/opencode")

    /** The file opencode writes to: same lookup order as its `globalConfigFile()`. */
    fun file(): File? = CANDIDATES.map { File(dir, it) }.firstOrNull { it.isFile }

    /**
     * Deletes [path] (e.g. `["mcp", "github"]`) from every global config file — opencode merges
     * `config.json`, `opencode.json` and `opencode.jsonc`, so a key left in any of them survives.
     * Comments and formatting are kept. Returns true when at least one file changed.
     */
    suspend fun remove(path: List<String>): Boolean = withContext(Dispatchers.IO) {
        require(path.isNotEmpty())
        var changed = false
        CANDIDATES.map { File(dir, it) }.filter { it.isFile }.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val updated = JsoncEdit.remove(text, path) ?: return@forEach
            write(file, updated)
            changed = true
        }
        changed
    }

    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    private companion object {
        val CANDIDATES = listOf("opencode.jsonc", "opencode.json", "config.json")
    }
}
