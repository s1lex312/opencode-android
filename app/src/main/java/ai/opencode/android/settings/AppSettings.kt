package ai.opencode.android.settings

import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.security.NetworkPolicy

enum class AuthMode { NONE, BASIC, BEARER }

/**
 * Where the app talks to opencode.
 *
 * [LOCAL] is the default: the app provisions and runs `opencode serve` inside its own sandbox via
 * proot and connects to `http://127.0.0.1:<port>`. [REMOTE] keeps the original behaviour of
 * pointing at an external server (laptop, VPS, Termux) over LAN/VPN.
 */
enum class ConnectionMode { LOCAL, REMOTE }

data class LocalServerSettings(
    /** Start the local server automatically when the app opens. */
    val enabled: Boolean = true,
    val port: Int = 4096,
    val serverCommand: String = "opencode serve --hostname 127.0.0.1 --port {port}",
    /** Advanced overrides; empty means "use the provisioned environment". */
    val rootfsPath: String = "",
    val prootPath: String = "",
    /** Bundle location; empty means "read local-env.tar.gz from APK assets". */
    val artifactUrl: String = "",
    val artifactSha256: String = "",
    val artifactVersion: String = "",
    val autoRestart: Boolean = true,
    val maxRestarts: Int = 5,
) {
    val url: String get() = "http://127.0.0.1:${port}"
}

/**
 * Everything that is persisted in plain DataStore. Secrets (password / token) live in
 * [ai.opencode.android.security.SecretStore] and are never part of this object.
 */
data class AppSettings(
    val connectionMode: ConnectionMode = ConnectionMode.LOCAL,
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

    /** Address the client actually connects to, given the selected mode. */
    val effectiveServerUrl: String
        get() = if (connectionMode == ConnectionMode.LOCAL) localServer.url else serverUrl

    val effectiveAuthMode: AuthMode
        get() = if (connectionMode == ConnectionMode.LOCAL) AuthMode.BASIC else authMode

    val effectiveUsername: String
        get() = if (connectionMode == ConnectionMode.LOCAL) "opencode" else username

    val isLocalMode: Boolean get() = connectionMode == ConnectionMode.LOCAL

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"
    }
}
