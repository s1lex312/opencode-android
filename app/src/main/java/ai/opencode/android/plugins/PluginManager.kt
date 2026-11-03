package ai.opencode.android.plugins

import ai.opencode.android.data.remote.ConfigJson
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.server.LocalConfigFile
import ai.opencode.android.settings.Hook
import ai.opencode.android.settings.HookEvent
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.settings.Snippet
import ai.opencode.android.settings.SystemPromptProfile
import ai.opencode.android.settings.Workbench
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/** A system prompt shipped by a plugin. */
@Serializable
data class PluginPrompt(val name: String, val text: String)

/** A snippet (`/name`) shipped by a plugin. */
@Serializable
data class PluginSnippet(val name: String, val body: String, val description: String = "", val send: Boolean = true)

/** A hook shipped by a plugin; installed disabled unless the user turns hooks on in the preview. */
@Serializable
data class PluginHook(
    val name: String,
    val event: HookEvent,
    val command: String,
    val matcher: String = "",
    val timeoutSeconds: Int = 60,
    val feedback: Boolean = false,
)

/**
 * `opencode-android-plugin` v1: a declarative bundle. Everything is configuration the app already
 * knows how to apply; nothing is executed at install time.
 *
 * * [mcp], [agent], [command] use opencode's own config format (`opencode.json`);
 * * [systemPrompts], [snippets], [hooks] go into the app's settings and workbench;
 * * [requires] lists tools from Settings → Environment tools the plugin needs (`node`, `python`, `git`).
 */
@Serializable
data class PluginManifest(
    val format: String = FORMAT,
    val version: Int = 1,
    val id: String,
    val name: String,
    val description: String = "",
    val pluginVersion: String = "1.0.0",
    val author: String = "",
    val homepage: String = "",
    val mcp: Map<String, JsonObject> = emptyMap(),
    val agent: Map<String, JsonObject> = emptyMap(),
    val command: Map<String, JsonObject> = emptyMap(),
    val systemPrompts: List<PluginPrompt> = emptyList(),
    val snippets: List<PluginSnippet> = emptyList(),
    val hooks: List<PluginHook> = emptyList(),
    val requires: List<String> = emptyList(),
) {
    companion object {
        const val FORMAT = "opencode-android-plugin"
        val ID = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
        val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}

/** What installing a manifest would do, shown before the user confirms. */
data class PluginPermissions(
    /** Commands the server will run (local MCP servers, hooks). */
    val programs: List<String>,
    /** Hosts remote MCP servers connect to. */
    val hosts: List<String>,
    val agents: List<String>,
    val commands: List<String>,
    val prompts: Int,
    val snippets: List<String>,
    val hooks: List<String>,
)

/** Names the app created for a plugin, so it can disable and remove exactly those. */
@Serializable
data class InstalledPlugin(
    val manifest: PluginManifest,
    val source: String,
    val sha256: String,
    val installedAt: Long,
    val enabled: Boolean = true,
    val hooksEnabled: Boolean = false,
    val promptIds: List<String> = emptyList(),
    val snippetIds: List<String> = emptyList(),
    val hookIds: List<String> = emptyList(),
    /** Parts that could not be applied (server offline, API without config editing). */
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CatalogEntry(
    val id: String,
    val name: String,
    val description: String = "",
    val url: String,
    val sha256: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class PluginCatalog(val format: String = "opencode-android-plugin-index", val plugins: List<CatalogEntry> = emptyList())

data class PluginPreview(val manifest: PluginManifest, val source: String, val sha256: String, val permissions: PluginPermissions)

data class PluginsState(
    val installed: List<InstalledPlugin> = emptyList(),
    val catalog: List<CatalogEntry> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val busy: Set<String> = emptySet(),
)

class PluginException(message: String) : Exception(message)

/**
 * Installs, toggles and removes declarative plugins. MCP servers go through the server's MCP API
 * (works for every API flavour), agents and commands through a config patch; removal on the
 * in-app server edits its config file and restarts it, since the API can only merge.
 */
class PluginManager(
    context: Context,
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val repository: SessionRepository,
    private val workbench: Workbench,
    private val localConfig: LocalConfigFile,
    private val restartServer: () -> Unit,
) {
    private val file = File(context.filesDir, "plugins.json")
    private val _state = MutableStateFlow(PluginsState(installed = load()))
    val state: StateFlow<PluginsState> = _state.asStateFlow()

    private fun load(): List<InstalledPlugin> = runCatching {
        JSON.decodeFromString(kotlinx.serialization.builtins.ListSerializer(InstalledPlugin.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private suspend fun persist(list: List<InstalledPlugin>) = withContext(Dispatchers.IO) {
        file.writeText(JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(InstalledPlugin.serializer()), list))
        _state.update { it.copy(installed = list) }
    }

    // ------------------------------------------------------------------ catalogue

    suspend fun loadCatalog() {
        _state.update { it.copy(catalogLoading = true, catalogError = null) }
        val url = settings.current().pluginCatalogUrl
        val result = runCatching {
            val text = download(url).decodeToString()
            JSON.decodeFromString(PluginCatalog.serializer(), text).plugins
        }
        _state.update {
            it.copy(
                catalogLoading = false,
                catalog = result.getOrDefault(it.catalog),
                catalogError = result.exceptionOrNull()?.message,
            )
        }
    }

    // ------------------------------------------------------------------ preview

    /** Downloads [url] (optionally `#sha256=<hex>` or [expectedSha256]) and describes what it would do. */
    suspend fun preview(url: String, expectedSha256: String? = null): PluginPreview {
        val (cleanUrl, pinned) = splitPin(url.trim())
        val bytes = download(cleanUrl)
        val digest = sha256(bytes)
        val expected = (expectedSha256?.takeIf { it.isNotBlank() } ?: pinned)?.lowercase()
        if (expected != null && expected != digest) throw PluginException("SHA-256 mismatch: expected $expected, got $digest")
        return previewText(bytes.decodeToString(), cleanUrl, digest)
    }

    fun previewText(text: String, source: String, digest: String = sha256(text.toByteArray())): PluginPreview {
        val manifest = parse(text)
        return PluginPreview(manifest, source, digest, permissions(manifest))
    }

    // ------------------------------------------------------------------ install

    suspend fun install(preview: PluginPreview, enableHooks: Boolean): InstalledPlugin {
        val manifest = preview.manifest
        busy(manifest.id, true)
        try {
            val previous = _state.value.installed.firstOrNull { it.manifest.id == manifest.id }
            if (previous != null) removeLocalParts(previous)
            val warnings = applyServerParts(manifest, enabled = true)
            val prefix = "plugin:${manifest.id}:"
            val prompts = manifest.systemPrompts.mapIndexed { index, prompt ->
                SystemPromptProfile(id = "$prefix$index", name = prompt.name, text = prompt.text)
            }
            if (prompts.isNotEmpty()) {
                settings.update { s -> s.copy(systemPrompts = s.systemPrompts.filterNot { it.id.startsWith(prefix) } + prompts) }
            }
            val snippets = snippetsOf(manifest)
            val hooks = manifest.hooks.mapIndexed { index, hook ->
                Hook(
                    id = "${prefix}hook-$index",
                    name = "${hook.name} (${manifest.name})",
                    event = hook.event,
                    command = hook.command,
                    matcher = hook.matcher,
                    enabled = enableHooks,
                    timeoutSeconds = hook.timeoutSeconds.coerceIn(1, 3_600),
                    feedback = hook.feedback,
                )
            }
            if (snippets.isNotEmpty() || hooks.isNotEmpty()) {
                workbench.update { data ->
                    data.copy(
                        snippets = data.snippets.filterNot { it.id.startsWith(prefix) } + snippets,
                        hooks = data.hooks.filterNot { it.id.startsWith(prefix) } + hooks,
                    )
                }
            }
            val installed = InstalledPlugin(
                manifest = manifest,
                source = preview.source,
                sha256 = preview.sha256,
                installedAt = System.currentTimeMillis(),
                enabled = true,
                hooksEnabled = enableHooks,
                promptIds = prompts.map { it.id },
                snippetIds = snippets.map { it.id },
                hookIds = hooks.map { it.id },
                warnings = warnings,
            )
            persist(_state.value.installed.filterNot { it.manifest.id == manifest.id } + installed)
            return installed
        } finally {
            busy(manifest.id, false)
        }
    }

    /** Turns the plugin's MCP servers, agents, snippets and hooks off or back on; nothing is deleted. */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        val plugin = _state.value.installed.firstOrNull { it.manifest.id == id } ?: return
        busy(id, true)
        try {
            val warnings = applyServerParts(plugin.manifest, enabled)
            val prefix = "plugin:$id:"
            workbench.update { data ->
                data.copy(
                    snippets = data.snippets.filterNot { it.id.startsWith(prefix) } + if (enabled) snippetsOf(plugin.manifest) else emptyList(),
                    hooks = data.hooks.map { if (it.id in plugin.hookIds) it.copy(enabled = enabled && plugin.hooksEnabled) else it },
                )
            }
            persist(_state.value.installed.map { if (it.manifest.id == id) it.copy(enabled = enabled, warnings = warnings) else it })
        } finally {
            busy(id, false)
        }
    }

    suspend fun uninstall(id: String): List<String> {
        val plugin = _state.value.installed.firstOrNull { it.manifest.id == id } ?: return emptyList()
        busy(id, true)
        try {
            val warnings = mutableListOf<String>()
            val local = settings.current().isLocalMode
            var restart = false
            plugin.manifest.mcp.keys.forEach { name ->
                when (val result = repository.mcpRemove(name)) {
                    is ApiResult.Ok -> if (!result.value) {
                        if (local && localConfig.remove(listOf("mcp", name))) restart = true else warnings += "mcp $name: disabled, not deleted"
                    }
                    is ApiResult.Err -> if (local && localConfig.remove(listOf("mcp", name))) restart = true else warnings += "mcp $name: ${result.message}"
                }
            }
            listOf("agent" to plugin.manifest.agent.keys, "command" to plugin.manifest.command.keys).forEach { (section, names) ->
                names.forEach { name ->
                    if (local && localConfig.remove(listOf(section, name))) {
                        restart = true
                    } else {
                        if (section == "agent") repository.patchGlobalConfig(buildJsonObject { put("agent", buildJsonObject { put(name, buildJsonObject { put("disable", true) }) }) })
                        warnings += "$section $name: ${if (section == "agent") "disabled" else "left in the server config"}"
                    }
                }
            }
            removeLocalParts(plugin)
            persist(_state.value.installed.filterNot { it.manifest.id == id })
            if (restart) restartServer()
            return warnings
        } finally {
            busy(id, false)
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun applyServerParts(manifest: PluginManifest, enabled: Boolean): List<String> {
        val warnings = mutableListOf<String>()
        manifest.mcp.forEach { (name, obj) ->
            val config = ConfigJson.decodeMcp(JsonObject(obj + ("enabled" to JsonPrimitive(enabled))))
            if (config == null) {
                warnings += "mcp $name: unknown type"
                return@forEach
            }
            when (val result = repository.mcpSave(name, config)) {
                is ApiResult.Ok -> if (!enabled) repository.mcpDisconnect(name)
                is ApiResult.Err -> warnings += "mcp $name: ${result.message}"
            }
        }
        if (manifest.agent.isNotEmpty() || manifest.command.isNotEmpty()) {
            val patch = buildJsonObject {
                if (manifest.agent.isNotEmpty()) {
                    put("agent", JsonObject(manifest.agent.mapValues { (_, value) -> JsonObject(value + ("disable" to JsonPrimitive(!enabled))) }))
                }
                if (manifest.command.isNotEmpty() && enabled) put("command", JsonObject(manifest.command))
            }
            if (patch.isNotEmpty()) {
                when (val result = repository.patchGlobalConfig(patch)) {
                    is ApiResult.Ok -> Unit
                    is ApiResult.Err -> warnings += "agents/commands: ${result.message}"
                }
            }
        }
        return warnings
    }

    private suspend fun removeLocalParts(plugin: InstalledPlugin) {
        val prefix = "plugin:${plugin.manifest.id}:"
        settings.update { s ->
            s.copy(
                systemPrompts = s.systemPrompts.filterNot { it.id.startsWith(prefix) },
                activeSystemPromptId = s.activeSystemPromptId?.takeIf { !it.startsWith(prefix) },
            )
        }
        workbench.update { data ->
            data.copy(
                snippets = data.snippets.filterNot { it.id.startsWith(prefix) },
                hooks = data.hooks.filterNot { it.id.startsWith(prefix) },
            )
        }
    }

    private fun snippetsOf(manifest: PluginManifest): List<Snippet> = manifest.snippets.mapIndexed { index, snippet ->
        Snippet(id = "plugin:${manifest.id}:snippet-$index", name = snippet.name, body = snippet.body, description = snippet.description, send = snippet.send)
    }

    private fun busy(id: String, on: Boolean) = _state.update { it.copy(busy = if (on) it.busy + id else it.busy - id) }

    private suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme?.lowercase() != "https") throw PluginException("Only https:// links are accepted")
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            val length = body.contentLength()
            if (length > MAX_BYTES) throw PluginException("File is larger than ${MAX_BYTES / 1024} KB")
            val bytes = body.byteStream().use { it.readNBytesCompat(MAX_BYTES + 1) }
            if (bytes.size > MAX_BYTES) throw PluginException("File is larger than ${MAX_BYTES / 1024} KB")
            bytes
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_BYTES = 512 * 1024

        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            coerceInputValues = true
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** `https://x/p.json#sha256=abc` → url and pinned digest. */
        fun splitPin(url: String): Pair<String, String?> {
            val marker = url.indexOf("#sha256=")
            return if (marker < 0) url to null else url.substring(0, marker) to url.substring(marker + 8).trim().lowercase()
        }

        /** Parses and validates a manifest; throws [PluginException] with a readable reason. */
        fun parse(text: String): PluginManifest {
            val manifest = runCatching { JSON.decodeFromString(PluginManifest.serializer(), text.trim()) }
                .getOrElse { throw PluginException("Not a plugin manifest: ${it.message?.lineSequence()?.firstOrNull()}") }
            if (manifest.format != PluginManifest.FORMAT) throw PluginException("Unknown format \"${manifest.format}\"")
            if (manifest.version != 1) throw PluginException("Manifest version ${manifest.version} needs a newer app")
            if (!PluginManifest.ID.matches(manifest.id)) throw PluginException("Bad plugin id \"${manifest.id}\"")
            if (manifest.name.isBlank()) throw PluginException("Plugin has no name")
            (manifest.mcp.keys + manifest.agent.keys + manifest.command.keys).forEach { name ->
                if (!PluginManifest.NAME.matches(name)) throw PluginException("Bad name \"$name\"")
            }
            manifest.mcp.forEach { (name, obj) ->
                if (ConfigJson.decodeMcp(obj) == null) throw PluginException("MCP server \"$name\" needs type local or remote")
            }
            manifest.snippets.forEach { if (!PluginManifest.NAME.matches(it.name)) throw PluginException("Bad snippet name \"${it.name}\"") }
            val empty = manifest.mcp.isEmpty() && manifest.agent.isEmpty() && manifest.command.isEmpty() &&
                manifest.systemPrompts.isEmpty() && manifest.snippets.isEmpty() && manifest.hooks.isEmpty()
            if (empty) throw PluginException("Plugin adds nothing")
            return manifest
        }

        fun permissions(manifest: PluginManifest): PluginPermissions {
            val programs = manifest.mcp.values.mapNotNull { obj ->
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "local") {
                    runCatching { obj["command"]!!.jsonArray.joinToString(" ") { it.jsonPrimitive.content } }.getOrNull()
                } else {
                    null
                }
            } + manifest.hooks.map { it.command }
            val hosts = manifest.mcp.values.mapNotNull { obj ->
                (obj["url"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { URI(it).host }.getOrNull() }
            }.distinct()
            return PluginPermissions(
                programs = programs,
                hosts = hosts,
                agents = manifest.agent.keys.toList(),
                commands = manifest.command.keys.toList(),
                prompts = manifest.systemPrompts.size,
                snippets = manifest.snippets.map { it.name },
                hooks = manifest.hooks.map { "${it.event.name.lowercase()}: ${it.name}" },
            )
        }
    }
}
