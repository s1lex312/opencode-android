package ai.opencode.android.notify

import ai.opencode.android.MainActivity
import ai.opencode.android.R
import ai.opencode.android.data.events.OpencodeEvent
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PermissionPolicy
import ai.opencode.android.data.model.PermissionRequest
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.settings.SettingsRepository
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns server events into system notifications while the app is in the background:
 *
 *  * a permission request, with **Allow / Always / Deny** buttons answered right from the shade;
 *  * "done" when a turn that ran in the background finishes;
 *  * an error when a session fails.
 *
 * The SSE stream lives in the process-wide [ServerConnectionManager], so this works as long as the
 * process does — in Local mode the foreground service keeps it alive.
 */
class AgentNotifier(
    private val context: Context,
    private val connection: ServerConnectionManager,
    private val repository: SessionRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {

    @Volatile
    private var foreground = false

    @Volatile
    private var enabled = true

    @Volatile
    private var policy = PermissionPolicy.ASK

    /** Sessions seen busy while backgrounded; only those produce a "done" notification. */
    private val busySessions = mutableSetOf<String>()
    private val titles = mutableMapOf<String, String>()

    fun start() {
        createChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                // Back in the app: the chat shows everything, clear finished-turn noise.
                NotificationManagerCompat.from(context).cancel(DONE_ID)
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
            }
        })
        scope.launch {
            settings.settings.collect {
                enabled = it.notifications
                policy = it.permissionPolicy
            }
        }
        scope.launch { connection.events.collect(::onEvent) }
    }

    private fun onEvent(event: OpencodeEvent) {
        when (event) {
            is OpencodeEvent.Status -> {
                if (event.busy) synchronized(busySessions) { busySessions += event.sessionId } else finished(event.sessionId)
            }

            is OpencodeEvent.Idle -> finished(event.sessionId)

            is OpencodeEvent.TextDelta, is OpencodeEvent.ToolStarted, is OpencodeEvent.PartUpserted -> {
                event.sessionId?.let { id -> synchronized(busySessions) { busySessions += id } }
            }

            is OpencodeEvent.PermissionAsked -> if (policy == PermissionPolicy.ASK) notifyPermission(event.request)

            is OpencodeEvent.PermissionResolved -> cancelPermission(event.permissionId)

            is OpencodeEvent.SessionFailed -> if (shouldNotify()) {
                notify(
                    id = ERROR_ID,
                    builder = base(CHANNEL_DONE, event.sessionId)
                        .setContentTitle(context.getString(R.string.notify_error_title))
                        .setContentText(event.message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(event.message)),
                )
            }

            else -> Unit
        }
    }

    private fun finished(sessionId: String) {
        val wasBusy = synchronized(busySessions) { busySessions.remove(sessionId) }
        if (!wasBusy || !shouldNotify()) return
        scope.launch {
            val title = titles[sessionId] ?: (repository.session(sessionId) as? ApiResult.Ok)?.value?.title?.also { titles[sessionId] = it }
            notify(
                id = DONE_ID,
                builder = base(CHANNEL_DONE, sessionId)
                    .setContentTitle(context.getString(R.string.notify_done_title))
                    .setContentText(title ?: context.getString(R.string.notify_done_body)),
            )
        }
    }

    private fun notifyPermission(request: PermissionRequest) {
        if (!shouldNotify()) return
        val body = buildString {
            append(request.title)
            if (request.resources.isNotEmpty()) append("\n").append(request.resources.take(3).joinToString("\n"))
        }
        val builder = base(CHANNEL_PERMISSION, request.sessionId)
            .setContentTitle(context.getString(R.string.notify_permission_title))
            .setContentText(request.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, context.getString(R.string.chat_permission_allow), action(request, PermissionDecision.ONCE))
            .addAction(0, context.getString(R.string.chat_permission_always), action(request, PermissionDecision.ALWAYS))
            .addAction(0, context.getString(R.string.chat_permission_deny), action(request, PermissionDecision.REJECT))
        notify(permissionNotificationId(request.id), builder)
    }

    private fun cancelPermission(permissionId: String) {
        NotificationManagerCompat.from(context).cancel(permissionNotificationId(permissionId))
    }

    /** Called by [NotificationActionReceiver]. */
    suspend fun reply(sessionId: String, permissionId: String, decision: PermissionDecision): Boolean {
        cancelPermission(permissionId)
        return repository.replyPermission(sessionId, permissionId, decision) is ApiResult.Ok
    }

    private fun shouldNotify(): Boolean = enabled && !foreground && canPost()

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun base(channel: String, sessionId: String?): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setContentIntent(openIntent(sessionId))

    private fun openIntent(sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (sessionId != null) putExtra(MainActivity.EXTRA_SESSION_ID, sessionId) }
        return PendingIntent.getActivity(
            context,
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(request: PermissionRequest, decision: PermissionDecision): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_PERMISSION)
            .putExtra(NotificationActionReceiver.EXTRA_SESSION, request.sessionId)
            .putExtra(NotificationActionReceiver.EXTRA_PERMISSION, request.id)
            .putExtra(NotificationActionReceiver.EXTRA_DECISION, decision.name)
        return PendingIntent.getBroadcast(
            context,
            (request.id + decision.name).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(id: Int, builder: NotificationCompat.Builder) {
        if (!canPost()) return
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PERMISSION, context.getString(R.string.notify_channel_permission), NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, context.getString(R.string.notify_channel_done), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun permissionNotificationId(permissionId: String): Int = PERMISSION_BASE + (permissionId.hashCode() and 0xFFFF)

    companion object {
        private const val CHANNEL_PERMISSION = "opencode_permissions"
        private const val CHANNEL_DONE = "opencode_turns"
        private const val DONE_ID = 5100
        private const val ERROR_ID = 5101
        private const val PERMISSION_BASE = 6000
    }
}
