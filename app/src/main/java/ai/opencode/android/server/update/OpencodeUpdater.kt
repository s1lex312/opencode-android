package ai.opencode.android.server.update

import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.remote.OpencodeJson
import ai.opencode.android.data.remote.arr
import ai.opencode.android.data.remote.bool
import ai.opencode.android.data.remote.long
import ai.opencode.android.data.remote.objects
import ai.opencode.android.data.remote.str
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.server.LocalServerManager
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.server.provision.EnvPaths
import ai.opencode.android.server.provision.TarGzExtractor
import ai.opencode.android.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A published opencode build for linux-arm64. */
data class OpencodeRelease(
    val version: String,
    val url: String,
    val sha256: String?,
    val sizeBytes: Long,
)

data class OpencodeUpdateState(
    val phase: Phase = Phase.IDLE,
    val installed: String? = null,
    val latest: String? = null,
    val progress: Float = 0f,
    val message: String? = null,
) {
    enum class Phase { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, RESTARTING, DONE, FAILED }

    val busy: Boolean get() = phase in setOf(Phase.CHECKING, Phase.DOWNLOADING, Phase.INSTALLING, Phase.RESTARTING)
}

/**
 * Keeps `rootfs/usr/local/bin/opencode` of the Local-mode environment current without a new APK.
 *
 * Source: the latest **stable** GitHub release of anomalyco/opencode (`opencode-linux-arm64.tar.gz`,
 * glibc, matching the Ubuntu rootfs). The asset digest published by GitHub is verified when present.
 * The swap is atomic (`opencode.new` → rename), the previous binary is kept as `opencode.bak`, and if
 * the server does not come back healthy the old binary is restored.
 */
class OpencodeUpdater(
    private val paths: EnvPaths,
    private val client: OkHttpClient,
    private val settings: SettingsRepository,
    private val connection: ServerConnectionManager,
    private val localServer: LocalServerManager,
    private val restartServer: () -> Unit,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(OpencodeUpdateState())
    val state: StateFlow<OpencodeUpdateState> = _state.asStateFlow()

    private val lock = Mutex()
    private var available: OpencodeRelease? = null

    private val binary: File get() = File(paths.rootfsDir, "usr/local/bin/opencode")
    private val backup: File get() = File(paths.rootfsDir, "usr/local/bin/opencode.bak")
    private val versionFile: File get() = File(paths.root, "opencode-version")

    /** Version of the binary on disk: our record after an update, else what the server reports. */
    fun installedVersion(): String? {
        versionFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val connected = connection.state.value as? ConnectionState.Connected ?: return null
        return connected.info.version?.removePrefix("v")
    }

    fun check() {
        scope.launch { checkNow() }
    }

    suspend fun checkNow(): OpencodeRelease? = lock.withLock {
        _state.update { it.copy(phase = OpencodeUpdateState.Phase.CHECKING, message = null, installed = installedVersion()) }
        val release = try {
            fetchLatest()
        } catch (t: Throwable) {
            _state.update { it.copy(phase = OpencodeUpdateState.Phase.FAILED, message = t.message ?: t.javaClass.simpleName) }
            return@withLock null
        }
        settings.update { it.copy(opencodeLastUpdateCheck = System.currentTimeMillis()) }
        val installed = installedVersion()
        val newer = installed == null || compareVersions(release.version, installed) > 0
        available = release.takeIf { newer }
        _state.update {
            it.copy(
                phase = if (newer) OpencodeUpdateState.Phase.AVAILABLE else OpencodeUpdateState.Phase.UP_TO_DATE,
                installed = installed,
                latest = release.version,
            )
        }
        available
    }

    fun install() {
        val release = available ?: return
        scope.launch { installNow(release) }
    }

    /**
     * Daily background check while the local server is up; installs only when no session is
     * running so a restart never cuts a turn short.
     */
    suspend fun maybeAutoUpdate(anySessionBusy: suspend () -> Boolean) {
        val current = settings.current()
        if (!current.isLocalMode || !current.opencodeAutoUpdate) return
        if (System.currentTimeMillis() - current.opencodeLastUpdateCheck < CHECK_INTERVAL_MS) return
        if (!binary.isFile) return
        val release = checkNow() ?: return
        if (anySessionBusy()) return
        installNow(release)
    }

    private suspend fun installNow(release: OpencodeRelease) = lock.withLock {
        val archive = File(paths.downloadDir, "opencode-${release.version}.tar.gz")
        val staging = File(paths.stagingDir, "opencode-update")
        try {
            download(release, archive)
            _state.update { it.copy(phase = OpencodeUpdateState.Phase.INSTALLING, progress = 0f) }
            withContext(Dispatchers.IO) {
                staging.deleteRecursively()
                archive.inputStream().use { TarGzExtractor.extract(it, staging) }
                val extracted = staging.walkTopDown().firstOrNull { it.isFile && it.name == "opencode" }
                    ?: throw IOException("opencode binary not found in ${archive.name}")
                val next = File(binary.parentFile, "opencode.new")
                extracted.copyTo(next, overwrite = true)
                if (!next.setExecutable(true, false)) throw IOException("Cannot mark opencode executable")
                if (binary.isFile) binary.copyTo(backup, overwrite = true)
                if (!next.renameTo(binary)) throw IOException("Cannot replace ${binary.path}")
                versionFile.writeText(release.version)
            }

            _state.update { it.copy(phase = OpencodeUpdateState.Phase.RESTARTING) }
            if (restartAndAwait()) {
                _state.update { it.copy(phase = OpencodeUpdateState.Phase.DONE, installed = release.version, message = null) }
                available = null
            } else {
                rollback()
                _state.update {
                    it.copy(
                        phase = OpencodeUpdateState.Phase.FAILED,
                        message = "opencode ${release.version} did not start; the previous version was restored",
                    )
                }
            }
        } catch (t: Throwable) {
            _state.update { it.copy(phase = OpencodeUpdateState.Phase.FAILED, message = t.message ?: t.javaClass.simpleName) }
        } finally {
            withContext(Dispatchers.IO) {
                archive.delete()
                staging.deleteRecursively()
            }
        }
    }

    private suspend fun restartAndAwait(): Boolean {
        val oldPid = localServer.state.value.pid
        restartServer()
        val ready = withTimeoutOrNull(RESTART_TIMEOUT_MS) {
            // A new process id tells the fresh READY apart from the one before the restart.
            localServer.state.first {
                (it.phase == LocalServerState.Phase.READY && it.pid != null && it.pid != oldPid) ||
                    it.phase == LocalServerState.Phase.FAILED
            }
        }
        return ready?.phase == LocalServerState.Phase.READY
    }

    private suspend fun rollback() {
        withContext(Dispatchers.IO) {
            if (backup.isFile) {
                backup.copyTo(binary, overwrite = true)
                binary.setExecutable(true, false)
            }
            versionFile.delete()
        }
        restartAndAwait()
    }

    private suspend fun fetchLatest(): OpencodeRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "opencode-android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered HTTP ${response.code}")
            val json = OpencodeJson.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject
                ?: throw IOException("Malformed GitHub response")
            if (json.bool("prerelease") == true) throw IOException("Latest release is a prerelease")
            val version = json.str("tag_name")?.removePrefix("v") ?: throw IOException("Release has no tag")
            val asset = json.arr("assets")?.objects()?.firstOrNull { it.str("name") == ASSET_NAME }
                ?: throw IOException("Release $version has no $ASSET_NAME")
            OpencodeRelease(
                version = version,
                url = asset.str("browser_download_url") ?: throw IOException("Asset has no URL"),
                sha256 = asset.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                sizeBytes = asset.long("size") ?: 0L,
            )
        }
    }

    private suspend fun download(release: OpencodeRelease, target: File) = withContext(Dispatchers.IO) {
        _state.update { it.copy(phase = OpencodeUpdateState.Phase.DOWNLOADING, progress = 0f, latest = release.version) }
        target.parentFile?.mkdirs()
        val request = Request.Builder().url(release.url).header("User-Agent", "opencode-android").build()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            var done = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
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
        val expected = release.sha256?.lowercase()
        if (expected != null && expected != actual) {
            target.delete()
            throw IOException("Checksum mismatch for ${release.version}")
        }
    }

    companion object {
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
        private const val ASSET_NAME = "opencode-linux-arm64.tar.gz"
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val RESTART_TIMEOUT_MS = 180_000L

        /** Numeric, dot-separated comparison; pre-release suffixes sort before the release. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val left = parts(a)
            val right = parts(b)
            for (i in 0 until maxOf(left.size, right.size)) {
                val diff = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
                if (diff != 0) return diff
            }
            val leftPre = '-' in a
            val rightPre = '-' in b
            return when {
                leftPre == rightPre -> 0
                leftPre -> -1
                else -> 1
            }
        }
    }
}
