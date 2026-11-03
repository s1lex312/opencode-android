package ai.opencode.android.backup

import ai.opencode.android.data.backup.ConfigBackup
import ai.opencode.android.security.EncryptedSecretStore
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.settings.Workbench
import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Android Auto Backup (Google account, device-to-device transfer) for the configuration.
 *
 * Instead of letting the system copy raw app files (the Keystore-encrypted secrets would be
 * unreadable on the next install anyway), the agent writes one [ConfigBackup] snapshot and backs up
 * only that. Keys and passwords go in only when the transport is end-to-end encrypted with the
 * screen lock or is a direct device-to-device transfer. Android restores the file while installing
 * the app; [ConfigBackup.onAppStart] applies it on the first launch.
 */
class OpencodeBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        // Before Android 9 the transport cannot tell whether it encrypts end to end: no secrets.
        val private = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            data.transportFlags and (FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED or FLAG_DEVICE_TO_DEVICE_TRANSFER) != 0
        val file = ConfigBackup.cloudFile(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val backup = ConfigBackup(
                context = this,
                settings = SettingsRepository(this),
                secrets = EncryptedSecretStore(this),
                workbench = Workbench(this),
                rootfs = { File(noBackupFilesDir, "opencode-env/rootfs") },
                scope = scope,
            )
            val text = runBlocking { backup.cloudSnapshot(withSecrets = private) }
            file.parentFile?.mkdirs()
            file.writeText(text)
            fullBackupFile(file, data)
        } catch (t: Throwable) {
            Log.w(TAG, "Backup skipped: ${t.javaClass.simpleName}")
        } finally {
            // Secrets must not linger on disk in the clear between backups.
            file.delete()
            scope.cancel()
        }
    }

    // Key/value backup is not used; Auto Backup calls onFullBackup / restores files itself.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    private companion object {
        const val TAG = "OpencodeBackupAgent"
    }
}
