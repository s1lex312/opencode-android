package ai.opencode.android.server

import ai.opencode.android.MainActivity
import ai.opencode.android.OpencodeApp
import ai.opencode.android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the optional proot-based opencode server alive while the app is
 * in the background. The service is only started on explicit user action from Settings; it stops
 * itself as soon as the supervisor reports STOPPED/FAILED.
 */
class LocalServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: ai.opencode.android.di.AppContainer
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        container = (application as OpencodeApp).container
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.localServerManager.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        if (observing) {
            // The service is already supervising a server; nothing else to do on a repeated start.
            return START_NOT_STICKY
        }
        observing = true

        startForeground(NOTIFICATION_ID, buildNotification("Starting opencode server…", indeterminate = true))

        scope.launch {
            val settings = container.settingsRepository.current().localServer
            container.localServerManager.start(settings)
        }

        scope.launch {
            // The state flow replays its current value (STOPPED before the launch completes), so
            // only react to STOPPED once the supervisor actually made progress.
            var sawProgress = false
            container.localServerManager.state.collect { state ->
                when (state.phase) {
                    LocalServerState.Phase.STARTING -> {
                        sawProgress = true
                        updateNotification(buildNotification("Starting opencode server…", indeterminate = true))
                    }

                    LocalServerState.Phase.READY -> {
                        sawProgress = true
                        updateNotification(
                            buildNotification(state.message ?: "opencode server is running", indeterminate = false),
                        )
                    }

                    LocalServerState.Phase.FAILED -> {
                        updateNotification(buildNotification(state.message ?: "opencode server failed", indeterminate = false))
                        stopSelf()
                    }

                    LocalServerState.Phase.STOPPED -> if (sawProgress) stopSelf()
                    LocalServerState.Phase.STOPPING -> updateNotification(
                        buildNotification("Stopping opencode server…", indeterminate = true),
                    )
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        container.localServerManager.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_local_server),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Status of the locally hosted opencode server"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, indeterminate: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.notification_local_server_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, indeterminate)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ACTION_STOP = "ai.opencode.android.action.STOP_LOCAL_SERVER"
        private const val CHANNEL_ID = "opencode_local_server"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocalServerService::class.java),
            )
        }

        /** Stops the server and the service without going through onStartCommand. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocalServerService::class.java)) }
        }
    }
}
