package ai.opencode.android.update

import ai.opencode.android.OpencodeApp
import ai.opencode.android.R
import ai.opencode.android.server.LocalServerService
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/** PackageInstaller session results and the "Install" action of the update notification. */
class AppUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? OpencodeApp)?.container ?: return
        when (intent.action) {
            ACTION_STATUS -> container.appUpdater.onInstallStatus(intent)
            ACTION_INSTALL -> {
                NotificationManagerCompat.from(context).cancel(AppUpdater.NOTIFICATION_ID)
                container.appUpdater.update()
            }
        }
    }

    companion object {
        const val ACTION_STATUS = "ai.opencode.android.update.STATUS"
        const val ACTION_INSTALL = "ai.opencode.android.update.INSTALL"
    }
}

/**
 * Runs right after this app was updated (by itself or by hand): the old process, and with it the
 * Local-mode server, is gone. Restart the server so the agent is reachable again without opening
 * the app, and say which version is now installed.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as? OpencodeApp)?.container ?: return
        val pending = goAsync()
        container.appScope.launch {
            try {
                val settings = runCatching { container.settingsRepository.current() }.getOrNull()
                if (settings?.isLocalMode == true && container.provisioner.isInstalled()) {
                    runCatching { LocalServerService.start(context) }
                }
                notifyUpdated(context)
            } finally {
                pending.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyUpdated(context: Context) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.app_update_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            context,
            4,
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.app_update_done_title, AppUpdater.currentVersion()))
            .setContentText(context.getString(R.string.app_update_done_body))
            .setContentIntent(open)
            .setAutoCancel(true)
        runCatching { NotificationManagerCompat.from(context).notify(AppUpdater.NOTIFICATION_ID, builder.build()) }
    }

    private companion object {
        const val CHANNEL = "opencode_app_updates"
    }
}
