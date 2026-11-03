package ai.opencode.android.data.backup

import ai.opencode.android.BuildConfig
import ai.opencode.android.security.SecretStore
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.settings.Workbench
import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

data class BackupState(
    val auto: Boolean = true,
    /** Where the automatic copy goes, human readable. */
    val location: String? = null,
    val lastAt: Long = 0L,
    val error: String? = null,
    /** A backup password is set, so keys travel (sealed) in the file. */
    val encrypted: Boolean = false,
    val busy: Boolean = false,
    /** Fresh install: offer to restore from the file before the first automatic copy. */
    val offerRestore: Boolean = false,
    /** Settings came back from Android's cloud backup during installation. */
    val restoredFromCloud: Boolean = false,
)

sealed interface RestoreResult {
    /** Applied; the app restarts to pick everything up. [secretsSkipped]: keys were not restored. */
    data class Ok(val secretsSkipped: Boolean) : RestoreResult

    data object NeedsPassword : RestoreResult

    data object WrongPassword : RestoreResult

    data class Failed(val message: String) : RestoreResult
}

/**
 * Keeps the configuration alive across an uninstall.
 *
 *  * **File in Downloads** — `Download/opencode/opencode-settings.json`, rewritten a few seconds
 *    after any change and when the app goes to the background. Files in shared storage outlive the
 *    app; after a reinstall the first launch offers to pick it. Keys and passwords are included
 *    only sealed with a backup password ([BackupCodec.seal]).
 *  * **Android cloud backup** — [ai.opencode.android.backup.OpencodeBackupAgent] hands the same
 *    snapshot to Google's backup and Android restores it while installing the app again; secrets
 *    go along only when that transport is end-to-end encrypted.
 */
class ConfigBackup(
    private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val workbench: Workbench,
    private val rootfs: () -> File,
    private val scope: CoroutineScope,
) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    // ------------------------------------------------------------------ lifecycle

    /**
     * Runs first thing in `Application.onCreate`: applies a cloud restore that Android dropped in
     * during installation, and notices a fresh install (no settings yet) to offer the file restore.
     */
    suspend fun onAppStart() {
        val fresh = !File(context.filesDir, DATASTORE_FILE).exists() && !prefs.getBoolean(KEY_INITIALIZED, false)
        prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
        val cloud = cloudFile(context)
        if (cloud.isFile) {
            val applied = runCatching {
                val snapshot = BackupCodec.decode(cloud.readText())
                apply(snapshot, snapshot.secrets)
                true
            }.getOrElse {
                Log.w(TAG, "Cloud restore failed: ${it.javaClass.simpleName}")
                false
            }
            cloud.delete()
            if (applied) {
                prefs.edit().putBoolean(KEY_OFFER, false).apply()
                _state.update { it.copy(restoredFromCloud = true, offerRestore = false) }
                return
            }
        }
        if (fresh) prefs.edit().putBoolean(KEY_OFFER, true).apply()
        _state.update { it.copy(offerRestore = prefs.getBoolean(KEY_OFFER, false)) }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            merge(settings.changes, workbench.data.map { })
                .drop(1)
                .debounce(AUTO_DELAY_MS)
                .collect { if (autoAllowed()) backupNow() }
        }
        scope.launch {
            if (autoAllowed()) backupNow()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (autoAllowed()) scope.launch { backupNow() }
            }
        })
    }

    private fun autoAllowed(): Boolean = _state.value.auto && !_state.value.offerRestore

    /** The user answered the first-run offer (restored, or chose to start clean). */
    fun dismissOffer() {
        prefs.edit().putBoolean(KEY_OFFER, false).apply()
        _state.update { it.copy(offerRestore = false) }
        scope.launch { backupNow() }
    }

    fun acknowledgeCloudRestore() = _state.update { it.copy(restoredFromCloud = false) }

    fun setAuto(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, enabled).apply()
        _state.update { it.copy(auto = enabled) }
        if (enabled) scope.launch { backupNow(force = true) }
    }

    /** Sets (or with null/blank removes) the password that seals keys inside the file. */
    fun setPassword(password: String?) {
        secrets.setProfileSecret(SecretStore.KEY_BACKUP_PASSWORD, password?.takeIf { it.isNotBlank() })
        prefs.edit().putString(KEY_PASSWORD_STAMP, UUID.randomUUID().toString()).apply()
        _state.update { it.copy(encrypted = !password.isNullOrBlank()) }
        scope.launch { backupNow(force = true) }
    }

    // ------------------------------------------------------------------ backup

    /** Writes the copy unless nothing changed since the last one. Returns false on failure. */
    suspend fun backupNow(force: Boolean = false): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            _state.update { it.copy(busy = true) }
            try {
                val password = secrets.profileSecret(SecretStore.KEY_BACKUP_PASSWORD)
                val collected = collectSecrets()
                val base = snapshot()
                val fingerprint = BackupCodec.fingerprint(base, collected.takeIf { password != null }, prefs.getString(KEY_PASSWORD_STAMP, null))
                if (!force && fingerprint == prefs.getString(KEY_FINGERPRINT, null) && targetUri() != null) {
                    return@withContext true
                }
                val sealed = password?.takeIf { !collected.isEmpty }?.let { BackupCodec.seal(collected, it) }
                val text = BackupCodec.encode(base.copy(encryptedSecrets = sealed))
                val location = write(text)
                prefs.edit()
                    .putString(KEY_FINGERPRINT, fingerprint)
                    .putLong(KEY_LAST_AT, System.currentTimeMillis())
                    .putString(KEY_LOCATION, location)
                    .remove(KEY_ERROR)
                    .apply()
                _state.update { it.copy(lastAt = System.currentTimeMillis(), location = location, error = null) }
                true
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                prefs.edit().putString(KEY_ERROR, message).apply()
                _state.update { it.copy(error = message) }
                false
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Snapshot for Android's cloud backup; [withSecrets] only for an end-to-end encrypted transport. */
    suspend fun cloudSnapshot(withSecrets: Boolean): String = withContext(Dispatchers.IO) {
        val base = snapshot()
        BackupCodec.encode(if (withSecrets) base.copy(secrets = collectSecrets()) else base)
    }

    private suspend fun snapshot(): BackupSnapshot {
        val (plain, _) = serverFiles()
        return BackupSnapshot(
            appVersion = BuildConfig.VERSION_NAME,
            createdAt = System.currentTimeMillis(),
            settings = BackupCodec.encodePrefs(settings.exportRaw()),
            files = mapOf(Workbench.FILE_NAME to workbench.exportJson()),
            server = plain,
        )
    }

    private fun collectSecrets(): BackupSecrets = BackupSecrets(store = secrets.exportAll(), server = serverFiles().second)

    /** opencode config of the in-app server: (non-secret config dir, secret sign-ins). */
    private fun serverFiles(): Pair<Map<String, String>, Map<String, String>> {
        val root = rootfs()
        if (!root.isDirectory) return emptyMap<String, String>() to emptyMap()
        val plain = linkedMapOf<String, String>()
        var total = 0L
        val configDir = File(root, CONFIG_DIR)
        if (configDir.isDirectory) {
            configDir.walkTopDown()
                .onEnter { it.name !in SKIPPED_DIRS }
                .filter { it.isFile && it.name !in SKIPPED_FILES && it.length() <= MAX_FILE_BYTES }
                .forEach { file ->
                    if (total + file.length() > MAX_SERVER_BYTES) return@forEach
                    val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                    if ('\u0000' in text) return@forEach
                    plain[file.relativeTo(root).invariantSeparatorsPath] = text
                    total += file.length()
                }
        }
        val secret = SECRET_FILES.mapNotNull { path ->
            val file = File(root, path)
            if (!file.isFile || file.length() > MAX_FILE_BYTES) return@mapNotNull null
            runCatching { path to file.readText() }.getOrNull()
        }.toMap()
        return plain to secret
    }

    // ------------------------------------------------------------------ where the file goes

    private fun targetUri(): Uri? = prefs.getString(KEY_TARGET, null)?.let(Uri::parse)

    /** Writes [text] to the remembered target, else to Download/opencode/. Returns a label. */
    private fun write(text: String): String {
        val resolver = context.contentResolver
        targetUri()?.let { uri ->
            val written = runCatching {
                resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("no stream")
            }.isSuccess
            if (written) return prefs.getString(KEY_LOCATION, null) ?: label(uri)
            prefs.edit().remove(KEY_TARGET).apply()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = ownDownload() ?: resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, MIME)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                },
            ) ?: throw IOException("Cannot create a file in Downloads")
            resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw IOException("Cannot write to Downloads")
            val label = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/${displayName(uri) ?: FILE_NAME}"
            prefs.edit().putString(KEY_TARGET, uri.toString()).putString(KEY_LOCATION, label).apply()
            return label
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) throw IOException(NO_STORAGE_ACCESS)
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        file.writeText(text)
        return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$FILE_NAME"
    }

    /** Our own earlier entry in Download/opencode (MediaStore only lists files this install created). */
    private fun ownDownload(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/", FILE_NAME),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) ContentUris.withAppendedId(collection, cursor.getLong(0)) else null }
        }.getOrNull()
    }

    /** A file the user picked (Save as… or the restored backup) becomes the automatic target. */
    suspend fun adoptTarget(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
        if (!persisted) return false
        prefs.edit().putString(KEY_TARGET, uri.toString()).putString(KEY_LOCATION, label(uri)).apply()
        _state.update { it.copy(location = label(uri)) }
        return backupNow(force = true)
    }

    private fun label(uri: Uri): String = displayName(uri) ?: uri.lastPathSegment ?: uri.toString()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    // ------------------------------------------------------------------ restore

    suspend fun read(uri: Uri): Result<BackupSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IOException("Cannot open the file")
            BackupCodec.decode(text)
        }
    }

    /**
     * Applies [snapshot]. Sealed keys need [password]; pass [skipSecrets] to restore everything
     * else without them. [source] (the picked file) becomes the automatic target afterwards.
     */
    suspend fun restore(snapshot: BackupSnapshot, password: String?, skipSecrets: Boolean = false, source: Uri? = null): RestoreResult {
        val opened = when {
            snapshot.secrets != null -> snapshot.secrets
            snapshot.encryptedSecrets == null || skipSecrets -> null
            password.isNullOrEmpty() -> return RestoreResult.NeedsPassword
            else -> try {
                withContext(Dispatchers.Default) { BackupCodec.open(snapshot.encryptedSecrets, password) }
            } catch (_: WrongBackupPassword) {
                return RestoreResult.WrongPassword
            } catch (t: Throwable) {
                return RestoreResult.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
        return try {
            withContext(Dispatchers.IO) { apply(snapshot, opened) }
            // Keep sealing keys with the same password from now on.
            if (opened != null && !password.isNullOrEmpty()) {
                secrets.setProfileSecret(SecretStore.KEY_BACKUP_PASSWORD, password)
                prefs.edit().putString(KEY_PASSWORD_STAMP, UUID.randomUUID().toString()).apply()
                _state.update { it.copy(encrypted = true) }
            }
            prefs.edit().putBoolean(KEY_OFFER, false).remove(KEY_FINGERPRINT).apply()
            _state.update { it.copy(offerRestore = false) }
            if (source != null) adoptTarget(source)
            RestoreResult.Ok(secretsSkipped = snapshot.encryptedSecrets != null && opened == null)
        } catch (t: Throwable) {
            RestoreResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private suspend fun apply(snapshot: BackupSnapshot, opened: BackupSecrets?) {
        settings.importRaw(BackupCodec.decodePrefs(snapshot.settings))
        snapshot.files[Workbench.FILE_NAME]?.let { workbench.importJson(it) }
        opened?.store?.takeIf { it.isNotEmpty() }?.let(secrets::importAll)
        val server = snapshot.server + opened?.server.orEmpty()
        if (server.isNotEmpty()) {
            // The environment may not be unpacked yet (fresh install): the server launcher applies
            // the pending files right before opencode starts.
            pendingFile(context).apply {
                parentFile?.mkdirs()
                writeText(PENDING_JSON.encodeToString(PENDING_SERIALIZER, server))
            }
            applyPendingServerFiles()
        }
    }

    /** Writes restored opencode config into the rootfs once it exists. Safe to call any time. */
    fun applyPendingServerFiles(): Boolean = applyPendingServerFiles(context, rootfs())

    companion object {
        private const val TAG = "ConfigBackup"
        private const val PREFS = "config_backup"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_OFFER = "offer_restore"
        private const val KEY_AUTO = "auto"
        private const val KEY_TARGET = "target_uri"
        private const val KEY_LOCATION = "location"
        private const val KEY_LAST_AT = "last_at"
        private const val KEY_ERROR = "error"
        private const val KEY_FINGERPRINT = "fingerprint"
        private const val KEY_PASSWORD_STAMP = "password_stamp"

        /** DataStore file of [SettingsRepository]; absent on a fresh install. */
        private const val DATASTORE_FILE = "datastore/opencode_settings.preferences_pb"

        const val FOLDER = "opencode"
        const val FILE_NAME = "opencode-settings.json"
        const val MIME = "application/json"
        const val NO_STORAGE_ACCESS = "no storage access"
        private const val AUTO_DELAY_MS = 3_000L

        private const val CONFIG_DIR = "root/.config/opencode"
        val SECRET_FILES = listOf("root/.local/share/opencode/auth.json", "root/.local/share/opencode/mcp-auth.json")
        private val SKIPPED_DIRS = setOf("node_modules", ".git", ".cache")
        private val SKIPPED_FILES = setOf("package-lock.json", "bun.lock", "bun.lockb")
        private const val MAX_FILE_BYTES = 256L * 1024
        private const val MAX_SERVER_BYTES = 2L * 1024 * 1024

        private val PENDING_JSON = Json { ignoreUnknownKeys = true }
        private val PENDING_SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        /** Snapshot that Android's cloud backup carries; restored there during installation. */
        fun cloudFile(context: Context): File = File(context.filesDir, "backup/cloud-snapshot.json")

        private fun pendingFile(context: Context): File = File(context.filesDir, "backup/pending-server.json")

        /** Also called by the server launcher, which has no [ConfigBackup] at hand. */
        fun applyPendingServerFiles(context: Context, rootfs: File): Boolean {
            val pending = pendingFile(context)
            if (!pending.isFile || !File(rootfs, "root").isDirectory) return false
            val files = runCatching { PENDING_JSON.decodeFromString(PENDING_SERIALIZER, pending.readText()) }.getOrNull()
            if (files == null) {
                pending.delete()
                return false
            }
            val base = File(rootfs, "root").canonicalFile
            files.forEach { (path, content) ->
                val target = File(rootfs, path).canonicalFile
                // Only below /root of the guest: a crafted backup must not write anywhere else.
                if (!target.path.startsWith(base.path + File.separator)) return@forEach
                runCatching {
                    target.parentFile?.mkdirs()
                    target.writeText(content)
                    if (path in SECRET_FILES) {
                        target.setReadable(false, false)
                        target.setReadable(true, true)
                    }
                }
            }
            pending.delete()
            return true
        }
    }

    private fun loadState(): BackupState = BackupState(
        auto = prefs.getBoolean(KEY_AUTO, true),
        location = prefs.getString(KEY_LOCATION, null),
        lastAt = prefs.getLong(KEY_LAST_AT, 0L),
        error = prefs.getString(KEY_ERROR, null),
        encrypted = secrets.profileSecret(SecretStore.KEY_BACKUP_PASSWORD) != null,
        offerRestore = prefs.getBoolean(KEY_OFFER, false),
    )
}
