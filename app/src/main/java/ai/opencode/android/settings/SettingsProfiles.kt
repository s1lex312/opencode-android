package ai.opencode.android.settings

import ai.opencode.android.data.backup.BackupCodec
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/** A named snapshot of every setting (and optionally hooks, snippets and the budget). */
@Serializable
data class SettingsProfile(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val settings: JsonObject,
    /** [Workbench.exportJson] output, or null when the profile leaves the workbench alone. */
    val workbench: String? = null,
)

@Serializable
data class SettingsProfilesFile(
    val format: String = FORMAT,
    val activeId: String? = null,
    val profiles: List<SettingsProfile> = emptyList(),
) {
    companion object {
        const val FORMAT = "opencode-android-settings-profiles"
    }
}

/**
 * Named settings profiles ("home", "work", "phone only"...). Applying one replaces every setting
 * except the device bookkeeping in [SettingsSchema.deviceKeys]; secrets are never part of a
 * profile, so switching does not touch keys or passwords.
 */
class SettingsProfiles(
    context: Context,
    private val settings: SettingsRepository,
    private val workbench: Workbench,
) {
    private val file = File(context.filesDir, "settings-profiles.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsProfilesFile> = _state.asStateFlow()

    private fun load(): SettingsProfilesFile = runCatching {
        json.decodeFromString(SettingsProfilesFile.serializer(), file.readText())
    }.getOrDefault(SettingsProfilesFile())

    private suspend fun save(value: SettingsProfilesFile) = withContext(Dispatchers.IO) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(SettingsProfilesFile.serializer(), value))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        _state.value = value
    }

    private suspend fun snapshot(): JsonObject =
        BackupCodec.encodePrefs(settings.exportRaw().filterKeys { it !in SettingsSchema.deviceKeys })

    /** Saves the current settings as a new profile, or over [replaceId]. */
    suspend fun saveCurrent(name: String, withWorkbench: Boolean, replaceId: String? = null): SettingsProfile {
        val now = System.currentTimeMillis()
        val existing = _state.value.profiles.firstOrNull { it.id == replaceId }
        val profile = SettingsProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { existing?.name ?: "Profile" },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            settings = snapshot(),
            workbench = if (withWorkbench) workbench.exportJson() else null,
        )
        val profiles = _state.value.profiles.filterNot { it.id == profile.id } + profile
        save(_state.value.copy(profiles = profiles, activeId = profile.id))
        return profile
    }

    suspend fun apply(id: String) {
        val profile = _state.value.profiles.firstOrNull { it.id == id } ?: return
        val keep = settings.exportRaw().filterKeys { it in SettingsSchema.deviceKeys }
        settings.importRaw(BackupCodec.decodePrefs(profile.settings).filterKeys { it !in SettingsSchema.deviceKeys } + keep)
        profile.workbench?.let { workbench.importJson(it) }
        save(_state.value.copy(activeId = id))
    }

    suspend fun rename(id: String, name: String) {
        save(_state.value.copy(profiles = _state.value.profiles.map { if (it.id == id) it.copy(name = name.trim()) else it }))
    }

    suspend fun delete(id: String) {
        val value = _state.value
        save(value.copy(profiles = value.profiles.filterNot { it.id == id }, activeId = value.activeId.takeIf { it != id }))
    }

    /** True when the current settings no longer match the active profile. */
    suspend fun activeModified(): Boolean {
        val active = _state.value.profiles.firstOrNull { it.id == _state.value.activeId } ?: return false
        return active.settings != snapshot()
    }

    fun export(id: String): String? {
        val profile = _state.value.profiles.firstOrNull { it.id == id } ?: return null
        return json.encodeToString(SettingsProfilesFile.serializer(), SettingsProfilesFile(profiles = listOf(profile)))
    }

    /** Imports every profile in a file written by [export]; returns how many were added. */
    suspend fun import(text: String): Int {
        val parsed = json.decodeFromString(SettingsProfilesFile.serializer(), text.trim())
        require(parsed.format == SettingsProfilesFile.FORMAT) { "not a settings profile file" }
        val fresh = parsed.profiles.map { it.copy(id = UUID.randomUUID().toString()) }
        save(_state.value.copy(profiles = _state.value.profiles + fresh))
        return fresh.size
    }
}
