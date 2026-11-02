package ai.opencode.android.di

import ai.opencode.android.BuildConfig
import ai.opencode.android.data.cache.SessionCache
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.security.EncryptedSecretStore
import ai.opencode.android.security.SecretStore
import ai.opencode.android.server.LocalRuntime
import ai.opencode.android.server.LocalServerManager
import ai.opencode.android.server.provision.EnvConfig
import ai.opencode.android.server.provision.EnvPaths
import ai.opencode.android.server.provision.EnvProvisioner
import ai.opencode.android.settings.SettingsRepository
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hand-rolled dependency container.
 *
 * The graph is small (one connection, one settings store, one provisioned environment, one
 * supervisor), so Hilt/Koin would add build complexity without buying anything. Dependencies are
 * constructor-injected into ViewModels, which keeps them unit-testable.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Application context, exposed for the few screens that need platform intents. */
    val context: Context get() = appContext

    /** Process-wide scope for work that must outlive any screen (SSE, provisioning, supervision). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val secretStore: SecretStore by lazy { EncryptedSecretStore(appContext) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    /** Location of the rootfs/proot bundle; `noBackupFilesDir` keeps ~300 MB out of cloud backups. */
    val envPaths: EnvPaths by lazy { EnvPaths(File(appContext.noBackupFilesDir, "opencode-env")) }

    /** Gradle product flavor: `full` embeds the bundle, `lite` downloads/imports it. */
    val flavor: String get() = BuildConfig.FLAVOR

    /** True when this APK ships `assets/local-env.tar.gz` (Full build). */
    val hasEmbeddedBundle: Boolean get() = EnvConfig.hasEmbeddedBundle(appContext)

    private val downloadClient by lazy { EnvConfig.downloadClient() }

    val provisioner: EnvProvisioner by lazy {
        EnvProvisioner(
            paths = envPaths,
            bundledManifest = { EnvConfig.assetManifest(appContext) },
            sourceFactory = { manifest -> EnvConfig.source(appContext, manifest, downloadClient) },
        )
    }

    val connectionManager: ServerConnectionManager by lazy {
        ServerConnectionManager(settingsRepository, secretStore, appScope)
    }

    val sessionCache: SessionCache by lazy { SessionCache(appContext) }

    val sessionRepository: SessionRepository by lazy {
        SessionRepository(connectionManager, sessionCache)
    }

    val localServerManager: LocalServerManager by lazy {
        LocalServerManager(appContext, appScope, provisioner, secretStore)
    }

    /**
     * LOCAL-mode orchestration. Creating it also teaches the HTTP client to wait for the local
     * server instead of failing fast while proot is still booting.
     */
    val localRuntime: LocalRuntime by lazy {
        LocalRuntime(
            settingsRepository = settingsRepository,
            secretStore = secretStore,
            provisioner = provisioner,
            manager = localServerManager,
            connection = connectionManager,
            scope = appScope,
            embeddedBundle = { hasEmbeddedBundle },
        ).also { runtime ->
            connectionManager.localReadiness = { runtime.awaitReadyForClient() }
        }
    }

    /** Starts the foreground service that supervises the in-app server (LOCAL mode). */
    fun startLocalServerService() {
        runCatching { ai.opencode.android.server.LocalServerService.start(appContext) }
    }

    fun stopLocalServerService() {
        runCatching { ai.opencode.android.server.LocalServerService.stop(appContext) }
        localRuntime.stop()
    }

    fun restartLocalServerService() {
        runCatching { ai.opencode.android.server.LocalServerService.restart(appContext) }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    /**
     * Warms the DataStore-backed settings so the first frame is not racing the disk read.
     * Deliberately free of side effects: the local server is started by the foreground service.
     */
    fun bootstrap() {
        appScope.launch { runCatching { settingsRepository.current() } }
        appScope.launch { runCatching { provisioner.refresh() } }
    }
}
