package ai.opencode.android.data.config

import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.server.provision.EnvPaths
import ai.opencode.android.settings.SettingsRepository
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** The config being edited: the in-app server's file, or the remote server's merged config. */
data class ConfigDocument(
    val text: String,
    /** Absolute file path for the in-app server; null for a remote server (edited through the API). */
    val path: String?,
)


sealed interface ConfigCheck {
    data class SyntaxError(val message: String) : ConfigCheck

    data class Checked(val errors: List<SchemaError>, val schemaUsed: Boolean) : ConfigCheck
}

/**
 * Raw editing of opencode's global config with a syntax check, validation against opencode's
 * published JSON Schema (cached for offline use) and a rollback history of the last versions.
 */
class ConfigEditor(
    context: Context,
    private val paths: EnvPaths,
    private val settings: SettingsRepository,
    private val repository: SessionRepository,
    private val client: OkHttpClient,
    private val versions: ConfigHistory,
) {
    private val schemaFile = File(context.filesDir, "opencode-config-schema.json")
    private val pretty = Json { prettyPrint = true }

    @Volatile
    private var validator: JsonSchemaValidator? = null

    private fun localFile(): File {
        val dir = File(paths.rootfsDir, "root/.config/opencode")
        return CANDIDATES.map { File(dir, it) }.firstOrNull { it.isFile } ?: File(dir, "opencode.json")
    }

    suspend fun load(): ConfigDocument = withContext(Dispatchers.IO) {
        if (settings.current().isLocalMode) {
            val file = localFile()
            val text = if (file.isFile) file.readText() else DEFAULT_TEXT
            ConfigDocument(text, file.absolutePath)
        } else {
            when (val result = repository.globalConfig()) {
                is ApiResult.Ok -> ConfigDocument(pretty.encodeToString(JsonObject.serializer(), result.value), null)
                is ApiResult.Err -> throw IOException(result.message)
            }
        }
    }

    /** Parses (JSONC allowed) and validates against the schema when it is available. */
    suspend fun check(text: String): ConfigCheck {
        val parsed = runCatching { Json.parseToJsonElement(Jsonc.strip(text)) }.getOrElse {
            return ConfigCheck.SyntaxError(it.message?.lineSequence()?.firstOrNull { line -> line.isNotBlank() } ?: "invalid JSON")
        }
        if (parsed !is JsonObject) return ConfigCheck.SyntaxError("the config must be a JSON object")
        val schema = schema()
        return ConfigCheck.Checked(schema?.validate(parsed).orEmpty(), schema != null)
    }

    /** Re-indents [text]; comments are lost, so only offered for plain JSON. */
    fun format(text: String): String? = runCatching {
        pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(Jsonc.strip(text))) + "\n"
    }.getOrNull()

    /**
     * Writes the in-app server's config (keeping the previous version in the history) or merges
     * into the remote one. Returns true when the in-app server must restart to read it.
     */
    suspend fun save(document: ConfigDocument, text: String): Boolean = withContext(Dispatchers.IO) {
        val path = document.path
        if (path == null) {
            val obj = Json.parseToJsonElement(Jsonc.strip(text)) as JsonObject
            when (val result = repository.patchGlobalConfig(obj)) {
                is ApiResult.Ok -> false
                is ApiResult.Err -> throw IOException(result.message)
            }
        } else {
            val file = File(path)
            file.parentFile?.mkdirs()
            if (file.isFile) versions.keep(file.readText(), ConfigHistory.Source.EDITOR)
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
            true
        }
    }

    fun history(): List<ConfigVersion> = versions.versions()

    fun read(version: ConfigVersion): String = versions.read(version)

    /** opencode's schema: memory, then the cached copy, then the network (and cache it). */
    private suspend fun schema(): JsonSchemaValidator? {
        validator?.let { return it }
        val cached = withContext(Dispatchers.IO) {
            runCatching { schemaFile.takeIf { it.isFile }?.readText()?.let { Json.parseToJsonElement(it) as JsonObject } }.getOrNull()
        }
        val fresh = if (cached == null || System.currentTimeMillis() - schemaFile.lastModified() > SCHEMA_MAX_AGE_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(Request.Builder().url(SCHEMA_URL).get().build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val text = response.body?.string().orEmpty()
                        (Json.parseToJsonElement(text) as JsonObject).also { schemaFile.writeText(text) }
                    }
                }.getOrNull()
            }
        } else {
            null
        }
        val root = fresh ?: cached ?: return null
        return JsonSchemaValidator(root).also { validator = it }
    }

    companion object {
        const val SCHEMA_URL = "https://opencode.ai/config.json"
        private const val SCHEMA_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        private val CANDIDATES = listOf("opencode.jsonc", "opencode.json", "config.json")
        private const val DEFAULT_TEXT = "{\n  \"\$schema\": \"https://opencode.ai/config.json\"\n}\n"
    }
}
