package ai.opencode.android.server

import ai.opencode.android.server.provision.EnvPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Direct access to the in-app server's global config (`/root/.config/opencode/opencode.json`
 * inside the rootfs). opencode's HTTP API only deep-merges patches, so removing a key (an MCP
 * server, an agent override) is only possible by editing the file; the caller restarts the server.
 */
class LocalConfigFile(private val paths: EnvPaths) {

    private val pretty = Json { prettyPrint = true }

    /** Same lookup order as opencode's `globalConfigFile()`. */
    fun file(): File? {
        val dir = File(paths.rootfsDir, "root/.config/opencode")
        return CANDIDATES.map { File(dir, it) }.firstOrNull { it.isFile }
    }

    /**
     * Deletes [path] (e.g. `["mcp", "github"]`). Returns false when there is no file, the file has
     * comments (JSONC) we must not destroy, or the key is absent.
     */
    suspend fun remove(path: List<String>): Boolean = withContext(Dispatchers.IO) {
        require(path.isNotEmpty())
        val file = file() ?: return@withContext false
        val text = runCatching { file.readText() }.getOrNull() ?: return@withContext false
        if (text.contains("//") && file.name.endsWith(".jsonc")) return@withContext false
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return@withContext false
        val updated = removeIn(root, path) ?: return@withContext false
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(pretty.encodeToString(JsonObject.serializer(), updated))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        true
    }

    private fun removeIn(obj: JsonObject, path: List<String>): JsonObject? {
        val key = path.first()
        if (key !in obj) return null
        if (path.size == 1) return JsonObject(obj - key)
        val child = obj[key] as? JsonObject ?: return null
        val updated = removeIn(child, path.drop(1)) ?: return null
        return JsonObject(obj + (key to updated))
    }

    private companion object {
        val CANDIDATES = listOf("opencode.jsonc", "opencode.json", "config.json")
    }
}
