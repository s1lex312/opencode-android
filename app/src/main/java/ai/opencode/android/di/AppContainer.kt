package ai.opencode.android.di

import ai.opencode.android.data.cache.SessionCache
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.security.EncryptedSecretStore
import ai.opencode.android.security.SecretStore
import ai.opencode.android.server.LocalServerManager
import ai.opencode.android.settings.SettingsRepository
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency container.
 *
 * The graph is tiny (one connection, one settings store, one cache) and everything is a lazily
 * created singleton, so Hilt/Koin would add build complexity without buying anything. All
 * dependencies are constructor-injected into ViewModels, which keeps them unit-testable.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Process-wide scope for work that must outlive any screen (the SSE subscription). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val secretStore: SecretStore by lazy { EncryptedSecretStore(appContext) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    val connectionManager: ServerConnectionManager by lazy {
        ServerConnectionManager(settingsRepository, secretStore, appScope)
    }

    val sessionCache: SessionCache by lazy { SessionCache(appContext) }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(connectionManager, sessionCache)
    }

    val localServerManager: LocalServerManager by lazy {
        LocalServerManager(appContext, appScope)
    }

    /**
     * Warms the DataStore-backed settings so the first frame is not racing the disk read.
     *
     * Deliberately free of side effects: the optional embedded server is started by
     * [LocalServerService] (foreground), never directly from [android.app.Application], because a
     * background service start outside a foreground service is not permitted on Android 12+.
     */
    fun bootstrap() {
        appScope.launch { runCatching { settingsRepository.current() } }
    }
}
