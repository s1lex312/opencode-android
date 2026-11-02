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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the in-app opencode server alive while the app is backgrounded,
 * the screen is off or the activity is destroyed.
 *
 * * `START_STICKY`: if Android kills the process, the service is recreated and the server is
 *   restarted (bounded by [LocalServerManager]'s restart policy).
 * * a partial wake lock keeps the CPU alive for the proot process during Doze; it is released as
 *   soon as the service stops, so idle battery impact is limited to actual server use.
 * * the notification is the only UI in the background: it shows state and offers Stop.
 */
class LocalServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var container: ai.opencode.android.di.AppContainer
    private var observing = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        container = (application as OpencodeApp).container
        createChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                container.localRuntime.stop()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_restarting), indeterminate = true))
                container.localRuntime.restart()
                observeIfNeeded()
                return START_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting), indeterminate = true))
        observeIfNeeded()

        // Idempotent: generates the password, checks the environment, starts proot and connects.
        container.localRuntime.start()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the server running when the user swipes the app away, unless they disabled it.
        scope.launch {
            val settings = runCatching { container.settingsRepository.current() }.getOrNull()
            if (settings == null || !settings.isLocalMode || !settings.localServer.enabled) {
                container.localRuntime.stop()
                stopSelf()
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        container.localRuntime.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeIfNeeded() {
        if (observing) return
        observing = true

        scope.launch {
            container.localRuntime.state.collect { runtime ->
                if (runtime.needsSetup) {
                    updateNotification(buildNotification(getString(R.string.notification_setup_required), indeterminate = false))
                    stopSelf()
                }
            }
        }

        scope.launch {
            var sawProgress = false
            container.localServerManager.state.collect { state ->
                when (state.phase) {
                    LocalServerState.Phase.STARTING -> {
                        sawProgress = true
                        updateNotification(
                            buildNotification(
                                state.message ?: getString(R.string.notification_starting),
                                indeterminate = true,
                            ),
                        )
                    }

                    LocalServerState.Phase.READY -> {
                        sawProgress = true
                        updateNotification(
                            buildNotification(
                                state.message ?: getString(R.string.notification_listening, state.port),
                                indeterminate = false,
                            ),
                        )
                    }

                    LocalServerState.Phase.STOPPING ->
                        updateNotification(buildNotification(getString(R.string.notification_stopping), indeterminate = true))

                    LocalServerState.Phase.FAILED -> {
                        updateNotification(
                            buildNotification(state.message ?: "opencode server failed", indeterminate = false),
                        )
                        stopSelf()
                    }

                    LocalServerState.Phase.STOPPED -> if (sawProgress) stopSelf()
                }
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_local_server),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
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
        val restartIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LocalServerService::class.java).setAction(ACTION_RESTART),
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
            .addAction(0, getString(R.string.notification_restart), restartIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode:local-server").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "ai.opencode.android.action.STOP_LOCAL_SERVER"
        const val ACTION_RESTART = "ai.opencode.android.action.RESTART_LOCAL_SERVER"
        private const val CHANNEL_ID = "opencode_local_server"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocalServerService::class.java),
            )
        }

        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocalServerService::class.java).setAction(ACTION_RESTART),
            )
        }

        /** Stops the server and the service without going through onStartCommand. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocalServerService::class.java)) }
        }
    }
}
