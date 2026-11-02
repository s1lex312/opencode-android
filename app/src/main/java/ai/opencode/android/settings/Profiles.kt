package ai.opencode.android.settings

import ai.opencode.android.data.remote.OpencodeJson
import ai.opencode.android.data.remote.bool
import ai.opencode.android.data.remote.objects
import ai.opencode.android.data.remote.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A named system prompt the user wrote. The active one is sent with every prompt on top of the
 * agent's own instructions (1.x: `system` field, 2.x: a session instruction entry).
 */
data class SystemPromptProfile(
    val id: String,
    val name: String,
    val text: String,
)

/**
 * A saved server connection. Secrets (password / token) are kept per profile in the encrypted
 * [ai.opencode.android.security.SecretStore] under [secretKey], never in DataStore.
 */
data class ServerProfile(
    val id: String,
    val name: String,
    val mode: ConnectionMode,
    val url: String = AppSettings.DEFAULT_SERVER_URL,
    val authMode: AuthMode = AuthMode.NONE,
    val username: String = AppSettings.DEFAULT_USERNAME,
    val allowPrivateNetworkCleartext: Boolean = true,
) {
    fun secretKey(kind: String): String = "profile:$id:$kind"

    /** True when [settings] currently point at this profile. */
    fun matches(settings: AppSettings): Boolean = when (mode) {
        ConnectionMode.LOCAL -> settings.connectionMode == ConnectionMode.LOCAL
        ConnectionMode.REMOTE -> settings.connectionMode == ConnectionMode.REMOTE &&
            settings.serverUrl.trimEnd('/') == url.trimEnd('/') &&
            settings.authMode == authMode &&
            settings.username == username
    }

    companion object {
        const val SECRET_PASSWORD = "password"
        const val SECRET_TOKEN = "token"
    }
}

/** Plain-JSON codecs; the lists are small and stored as one DataStore string each. */
internal object ProfileCodec {

    fun encodePrompts(list: List<SystemPromptProfile>): String = buildJsonArray {
        list.forEach { prompt ->
            add(
                buildJsonObject {
                    put("id", prompt.id)
                    put("name", prompt.name)
                    put("text", prompt.text)
                },
            )
        }
    }.toString()

    fun decodePrompts(raw: String?): List<SystemPromptProfile> = parseArray(raw).mapNotNull { obj ->
        SystemPromptProfile(
            id = obj.str("id") ?: return@mapNotNull null,
            name = obj.str("name").orEmpty(),
            text = obj.str("text").orEmpty(),
        )
    }

    fun encodeServers(list: List<ServerProfile>): String = buildJsonArray {
        list.forEach { profile ->
            add(
                buildJsonObject {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("mode", profile.mode.name)
                    put("url", profile.url)
                    put("authMode", profile.authMode.name)
                    put("username", profile.username)
                    put("allowCleartext", profile.allowPrivateNetworkCleartext)
                },
            )
        }
    }.toString()

    fun decodeServers(raw: String?): List<ServerProfile> = parseArray(raw).mapNotNull { obj ->
        ServerProfile(
            id = obj.str("id") ?: return@mapNotNull null,
            name = obj.str("name").orEmpty(),
            mode = runCatching { ConnectionMode.valueOf(obj.str("mode").orEmpty()) }.getOrDefault(ConnectionMode.REMOTE),
            url = obj.str("url") ?: AppSettings.DEFAULT_SERVER_URL,
            authMode = runCatching { AuthMode.valueOf(obj.str("authMode").orEmpty()) }.getOrDefault(AuthMode.NONE),
            username = obj.str("username") ?: AppSettings.DEFAULT_USERNAME,
            allowPrivateNetworkCleartext = obj.bool("allowCleartext") ?: true,
        )
    }

    private fun parseArray(raw: String?): List<JsonObject> {
        if (raw.isNullOrBlank()) return emptyList()
        val element = runCatching { OpencodeJson.parseToJsonElement(raw) }.getOrNull()
        return (element as? JsonArray)?.objects().orEmpty()
    }
}
