package ai.opencode.android.update

import ai.opencode.android.BuildConfig
import ai.opencode.android.MainActivity
import ai.opencode.android.R
import ai.opencode.android.data.remote.OpencodeJson
import ai.opencode.android.data.remote.arr
import ai.opencode.android.data.remote.bool
import ai.opencode.android.data.remote.long
import ai.opencode.android.data.remote.objects
import ai.opencode.android.data.remote.str
import ai.opencode.android.server.update.OpencodeUpdater
import ai.opencode.android.settings.SettingsRepository
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** An APK of this app published as a GitHub release asset. */
data class AppRelease(
    val version: String,
    val url: String,
    val sha256: String?,
    val sizeBytes: Long,
    val notes: String,
)

data class AppUpdateState(
    val phase: Phase = Phase.IDLE,
    val current: String = AppUpdater.currentVersion(),
    val latest: String? = null,
    val progress: Float = 0f,
    val sizeBytes: Long = 0L,
    val notes: String = "",
    val message: String? = null,
) {
    enum class Phase {
        IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, INSTALLING,

        /** Android needs "Install unknown apps" for this app first. */
        NEEDS_PERMISSION,

        /** The new APK is signed with another key: Android only installs it after an uninstall. */
        SIGNATURE_MISMATCH,
        FAILED,
    }

    val busy: Boolean get() = phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.INSTALLING
}

/**
 * Updates the app itself from the GitHub releases of this repository.
 *
 * Checks at start and every few hours while the process lives, downloads the APK of the same
 * flavour (Lite on any network, the ~125 MB Full only on unmetered ones), verifies the GitHub digest
 * and that the APK carries the installed signing certificate, then installs it through a
 * [PackageInstaller] session. On Android 12+ the session asks for no confirmation
 * ([PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED]); Android honours that once the app
 * installed the current version itself, so only the very first in-app update shows the system
 * dialog. Automatic installs wait until the app is in the background and no agent turn is running,
 * because replacing the APK restarts the process.
 */
class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val anySessionBusy: suspend () -> Boolean,
) {

    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val lock = Mutex()
    private var release: AppRelease? = null
    private var apk: File? = null
    private val dir: File get() = File(context.cacheDir, "updates")

    fun start() {
        createChannel()
        cleanup()
        scope.launch {
            delay(START_DELAY_MS)
            while (true) {
                runCatching { autoCycle() }
                delay(CHECK_INTERVAL_MS)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                scope.launch { runCatching { maybeAutoInstall() } }
            }
        })
    }

    private suspend fun autoCycle() {
        val current = settings.current()
        if (!current.appAutoUpdate) return
        val found = checkNow() ?: return
        if (!autoDownloadAllowed()) {
            notifyAvailable(found, ready = false)
            return
        }
        if (!downloadNow()) return
        if (current.appAutoInstall && !foreground() && !anySessionBusy()) installNow() else notifyAvailable(found, ready = true)
    }

    private suspend fun maybeAutoInstall() {
        if (_state.value.phase != AppUpdateState.Phase.READY) return
        if (!settings.current().appAutoInstall || anySessionBusy()) return
        installNow()
    }

    fun check() {
        scope.launch { checkNow() }
    }

    /** Download (if needed) and install; the button in Settings. */
    fun update() {
        scope.launch {
            if (release == null && checkNow() == null) return@launch
            if (apk?.isFile != true && !downloadNow()) return@launch
            installNow()
        }
    }

    suspend fun checkNow(): AppRelease? = lock.withLock {
        _state.update { it.copy(phase = AppUpdateState.Phase.CHECKING, message = null) }
        val latest = try {
            fetchLatest()
        } catch (t: Throwable) {
            _state.update { it.copy(phase = AppUpdateState.Phase.FAILED, message = t.message ?: t.javaClass.simpleName) }
            return@withLock null
        }
        val newer = OpencodeUpdater.compareVersions(latest.version, currentVersion()) > 0
        release = latest.takeIf { newer }
        val downloaded = release?.let { existingDownload(it) }
        apk = downloaded
        _state.update {
            it.copy(
                phase = when {
                    !newer -> AppUpdateState.Phase.UP_TO_DATE
                    downloaded != null -> AppUpdateState.Phase.READY
                    else -> AppUpdateState.Phase.AVAILABLE
                },
                latest = latest.version,
                sizeBytes = latest.sizeBytes,
                notes = latest.notes,
            )
        }
        release
    }

    private suspend fun downloadNow(): Boolean = lock.withLock {
        val target = release ?: return@withLock false
        existingDownload(target)?.let {
            apk = it
            _state.update { s -> s.copy(phase = AppUpdateState.Phase.READY) }
            return@withLock true
        }
        val file = File(dir, "opencode-android-${BuildConfig.FLAVOR}-${target.version}.apk")
        try {
            download(target, file)
            verifyApk(file)?.let { problem ->
                file.delete()
                _state.update { it.copy(phase = problem.first, message = problem.second) }
                return@withLock false
            }
            apk = file
            _state.update { it.copy(phase = AppUpdateState.Phase.READY, progress = 1f) }
            true
        } catch (t: Throwable) {
            file.delete()
            _state.update { it.copy(phase = AppUpdateState.Phase.FAILED, message = t.message ?: t.javaClass.simpleName) }
            false
        }
    }

    private suspend fun installNow() = lock.withLock {
        val file = apk?.takeIf { it.isFile } ?: return@withLock
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(phase = AppUpdateState.Phase.NEEDS_PERMISSION) }
            notifyAvailable(release ?: return@withLock, ready = true)
            return@withLock
        }
        _state.update { it.copy(phase = AppUpdateState.Phase.INSTALLING, message = null) }
        try {
            withContext(Dispatchers.IO) { commitSession(file) }
        } catch (t: Throwable) {
            _state.update { it.copy(phase = AppUpdateState.Phase.FAILED, message = t.message ?: t.javaClass.simpleName) }
        }
    }

    private fun commitSession(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(context, AppUpdateReceiver::class.java).setAction(AppUpdateReceiver.ACTION_STATUS)
            // Mutable: the installer adds the status extras to this intent.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** [AppUpdateReceiver] forwards the session result here. */
    fun onInstallStatus(intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (foreground()) {
                    runCatching { context.startActivity(confirm) }.onFailure { notifyConfirm(confirm) }
                } else {
                    // Background activity starts are blocked: let the user tap the notification.
                    notifyConfirm(confirm)
                }
                _state.update { it.copy(phase = AppUpdateState.Phase.READY) }
            }

            PackageInstaller.STATUS_SUCCESS -> _state.update { it.copy(phase = AppUpdateState.Phase.UP_TO_DATE) }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val phase = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    AppUpdateState.Phase.SIGNATURE_MISMATCH
                } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    AppUpdateState.Phase.READY
                } else {
                    AppUpdateState.Phase.FAILED
                }
                _state.update { it.copy(phase = phase, message = message ?: "status $status") }
            }
        }
    }

    /** Opens "Install unknown apps" for this app. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------ GitHub

    private suspend fun fetchLatest(): AppRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "opencode-android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered HTTP ${response.code}")
            val json = OpencodeJson.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                ?: throw IOException("Malformed GitHub response")
            parseRelease(json, BuildConfig.FLAVOR)
        }
    }

    private suspend fun download(release: AppRelease, target: File) = withContext(Dispatchers.IO) {
        _state.update { it.copy(phase = AppUpdateState.Phase.DOWNLOADING, progress = 0f) }
        target.parentFile?.mkdirs()
        dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
        val partial = File(target.parentFile, target.name + ".part")
        val request = Request.Builder().url(release.url).header("User-Agent", "opencode-android").build()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            var done = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) _state.update { it.copy(progress = (done.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && release.sha256.lowercase() != actual) {
            partial.delete()
            throw IOException("Checksum mismatch for ${release.version}")
        }
        if (!partial.renameTo(target)) throw IOException("Cannot store the update")
    }

    private fun existingDownload(release: AppRelease): File? =
        File(dir, "opencode-android-${BuildConfig.FLAVOR}-${release.version}.apk").takeIf { it.isFile && verifyApk(it) == null }

    /** Null when [file] can update this install; otherwise the phase and a reason. */
    private fun verifyApk(file: File): Pair<AppUpdateState.Phase, String>? {
        val pm = context.packageManager
        val archive = archiveInfo(pm, file.path)
            ?: return AppUpdateState.Phase.FAILED to "The downloaded file is not a valid APK"
        if (archive.packageName != context.packageName) {
            return AppUpdateState.Phase.FAILED to "The APK is for ${archive.packageName}, not ${context.packageName}"
        }
        val installed = runCatching { installedInfo(pm) }.getOrNull() ?: return null
        val newCerts = certificates(archive)
        val oldCerts = certificates(installed)
        if (newCerts.isNotEmpty() && oldCerts.isNotEmpty() && newCerts.intersect(oldCerts).isEmpty()) {
            return AppUpdateState.Phase.SIGNATURE_MISMATCH to "signed with a different key"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(path, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageInfo(context.packageName, flags)
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun autoDownloadAllowed(): Boolean {
        if (BuildConfig.FLAVOR != "full") return true
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return !connectivity.isActiveNetworkMetered
    }

    private fun foreground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** Downloads of versions that are installed by now are dead weight. */
    private fun cleanup() {
        scope.launch(Dispatchers.IO) {
            dir.listFiles()?.forEach { file ->
                val version = Regex("-(\\d+(?:\\.\\d+)*)\\.apk$").find(file.name)?.groupValues?.get(1)
                if (version == null || OpencodeUpdater.compareVersions(version, currentVersion()) <= 0) file.delete()
            }
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.app_update_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun notifyAvailable(release: AppRelease, ready: Boolean) {
        if (foreground()) return
        val install = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, AppUpdateReceiver::class.java).setAction(AppUpdateReceiver.ACTION_INSTALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.app_update_notify_title, release.version))
            .setContentText(context.getString(if (ready) R.string.app_update_notify_ready else R.string.app_update_notify_available))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.app_update_install), install)
        post(builder)
    }

    private fun notifyConfirm(confirm: Intent) {
        val tap = PendingIntent.getActivity(context, 2, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.app_update_notify_title, release?.version ?: _state.value.latest.orEmpty()))
            .setContentText(context.getString(R.string.app_update_notify_confirm))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        post(builder)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        3,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("MissingPermission")
    private fun post(builder: NotificationCompat.Builder) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build()) }
    }

    companion object {
        const val REPOSITORY = "s1lex312/opencode-android"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        private const val CHANNEL = "opencode_app_updates"
        const val NOTIFICATION_ID = 5200
        private const val START_DELAY_MS = 20_000L
        private const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** `1.6.0` out of `1.6.0-lite-debug`. */
        fun currentVersion(): String = BuildConfig.VERSION_NAME.substringBefore('-')

        /** Picks this flavour's APK (`opencode-android-<flavor>-v1.6.0.apk`) from a release. */
        fun parseRelease(json: JsonObject, flavor: String): AppRelease {
            if (json.bool("draft") == true || json.bool("prerelease") == true) throw IOException("Latest release is not stable")
            val version = json.str("tag_name")?.removePrefix("v") ?: throw IOException("Release has no tag")
            val asset = json.arr("assets")?.objects()?.firstOrNull {
                val name = it.str("name").orEmpty()
                name.startsWith("opencode-android-$flavor-") && name.endsWith(".apk")
            } ?: throw IOException("Release $version has no $flavor APK")
            return AppRelease(
                version = version,
                url = asset.str("browser_download_url") ?: throw IOException("Asset has no URL"),
                sha256 = asset.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                sizeBytes = asset.long("size") ?: 0L,
                notes = json.str("body").orEmpty(),
            )
        }
    }
}
