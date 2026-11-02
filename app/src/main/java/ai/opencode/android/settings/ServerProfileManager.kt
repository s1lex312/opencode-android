package ai.opencode.android.settings

import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.security.NetworkPolicy
import ai.opencode.android.security.SecretStore
import ai.opencode.android.server.LocalRuntime
import java.util.UUID

/**
 * Saved server connections (phone, laptop, VPS…) and one-tap switching between them.
 *
 * Activating a profile writes its fields into the live [AppSettings], copies its secrets into the
 * active password/token slots and reconnects; Local profiles start the in-app server, Remote ones
 * stop it.
 */
class ServerProfileManager(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val connection: ServerConnectionManager,
    private val localRuntime: () -> LocalRuntime,
) {

    sealed interface SaveResult {
        data class Ok(val profile: ServerProfile) : SaveResult
        data class InvalidUrl(val reason: String) : SaveResult
    }

    /** Snapshot of the current connection as a new profile. */
    suspend fun saveCurrent(name: String): ServerProfile {
        val current = settings.current()
        val profile = ServerProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { defaultName(current) },
            mode = current.connectionMode,
            url = current.serverUrl,
            authMode = current.authMode,
            username = current.username,
            allowPrivateNetworkCleartext = current.allowPrivateNetworkCleartext,
        )
        if (profile.mode == ConnectionMode.REMOTE) {
            secrets.setProfileSecret(profile.secretKey(ServerProfile.SECRET_PASSWORD), secrets.password())
            secrets.setProfileSecret(profile.secretKey(ServerProfile.SECRET_TOKEN), secrets.token())
        }
        settings.update { it.copy(serverProfiles = it.serverProfiles + profile) }
        return profile
    }

    /**
     * Adds or replaces [profile]. Blank [password]/[token] keep the stored secret; `null` clears it
     * only for a new profile.
     */
    suspend fun save(profile: ServerProfile, password: String?, token: String?): SaveResult {
        var normalized = profile.copy(name = profile.name.trim())
        if (profile.mode == ConnectionMode.REMOTE) {
            when (val policy = NetworkPolicy.normalize(profile.url, profile.allowPrivateNetworkCleartext)) {
                is NetworkPolicy.Result.Rejected -> return SaveResult.InvalidUrl(policy.reason)
                is NetworkPolicy.Result.Allowed -> normalized = normalized.copy(url = policy.url)
            }
        }
        if (!password.isNullOrEmpty()) secrets.setProfileSecret(normalized.secretKey(ServerProfile.SECRET_PASSWORD), password)
        if (!token.isNullOrEmpty()) secrets.setProfileSecret(normalized.secretKey(ServerProfile.SECRET_TOKEN), token)
        val wasActive = settings.current().serverProfiles.firstOrNull { it.id == profile.id }?.matches(settings.current()) == true
        settings.update { current ->
            val list = current.serverProfiles
            val updated = if (list.any { it.id == normalized.id }) list.map { if (it.id == normalized.id) normalized else it } else list + normalized
            current.copy(serverProfiles = updated)
        }
        if (wasActive) activate(normalized.id)
        return SaveResult.Ok(normalized)
    }

    suspend fun delete(id: String) {
        val profile = settings.current().serverProfiles.firstOrNull { it.id == id } ?: return
        secrets.setProfileSecret(profile.secretKey(ServerProfile.SECRET_PASSWORD), null)
        secrets.setProfileSecret(profile.secretKey(ServerProfile.SECRET_TOKEN), null)
        settings.update { it.copy(serverProfiles = it.serverProfiles.filterNot { p -> p.id == id }) }
    }

    suspend fun activate(id: String) {
        val profile = settings.current().serverProfiles.firstOrNull { it.id == id } ?: return
        settings.update {
            when (profile.mode) {
                ConnectionMode.LOCAL -> it.copy(connectionMode = ConnectionMode.LOCAL)
                ConnectionMode.REMOTE -> it.copy(
                    connectionMode = ConnectionMode.REMOTE,
                    serverUrl = profile.url,
                    authMode = profile.authMode,
                    username = profile.username,
                    allowPrivateNetworkCleartext = profile.allowPrivateNetworkCleartext,
                    // Models and agents differ per server; let each fall back to its default.
                    model = null,
                    agent = "",
                )
            }
        }
        if (profile.mode == ConnectionMode.LOCAL) {
            localRuntime().start()
        } else {
            secrets.setPassword(secrets.profileSecret(profile.secretKey(ServerProfile.SECRET_PASSWORD)))
            secrets.setToken(secrets.profileSecret(profile.secretKey(ServerProfile.SECRET_TOKEN)))
            localRuntime().stop()
            connection.connect(force = true)
        }
    }

    fun hasSecret(profile: ServerProfile, kind: String): Boolean = !secrets.profileSecret(profile.secretKey(kind)).isNullOrEmpty()

    private fun defaultName(settings: AppSettings): String = when (settings.connectionMode) {
        ConnectionMode.LOCAL -> "Phone"
        ConnectionMode.REMOTE -> runCatching { java.net.URI(settings.serverUrl).host }.getOrNull() ?: settings.serverUrl
    }
}
