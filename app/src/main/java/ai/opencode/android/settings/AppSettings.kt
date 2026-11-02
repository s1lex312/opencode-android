package ai.opencode.android.settings

import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.security.NetworkPolicy

enum class AuthMode { NONE, BASIC, BEARER }

data class LocalServerSettings(
    val enabled: Boolean = false,
    val rootfsPath: String = "",
    val prootPath: String = "",
    val port: Int = 4096,
    val serverCommand: String = "opencode serve --hostname 127.0.0.1 --port {port}",
)

/**
 * Everything that is persisted in plain DataStore. Secrets (password / token) live in
 * [ai.opencode.android.security.SecretStore] and are never part of this object.
 */
data class AppSettings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val authMode: AuthMode = AuthMode.NONE,
    val username: String = "opencode",
    val allowPrivateNetworkCleartext: Boolean = true,
    val autoConnect: Boolean = true,
    val model: ModelRef? = null,
    val agent: String = "",
    val permissionPolicy: PermissionPolicy = PermissionPolicy.ASK,
    val requestTimeoutSeconds: Int = 300,
    val lastSessionId: String? = null,
    val localServer: LocalServerSettings = LocalServerSettings(),
) {
    /** True when the configured address passes the cleartext policy. */
    val serverUrlValid: Boolean
        get() = NetworkPolicy.normalize(serverUrl, allowPrivateNetworkCleartext) is NetworkPolicy.Result.Allowed

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"
    }
}
