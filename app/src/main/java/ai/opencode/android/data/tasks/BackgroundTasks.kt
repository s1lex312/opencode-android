package ai.opencode.android.data.tasks

import ai.opencode.android.MainActivity
import ai.opencode.android.R
import ai.opencode.android.data.events.OpencodeEvent
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.ModelRef
import ai.opencode.android.data.model.PromptAttachment
import ai.opencode.android.data.model.PromptRequest
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.data.repo.SessionRepository
import ai.opencode.android.settings.BackgroundTask
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.settings.TaskStatus
import ai.opencode.android.settings.Workbench
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fire-and-forget prompts: each runs in a new chat and ends with a notification carrying the
 * answer, whether the app is open or not. State lives in [Workbench] so tasks survive restarts;
 * a task whose completion event was missed (process killed, SSE dropped) is reconciled against
 * the server's session status on the next connection.
 */
class BackgroundTasks(
    private val context: Context,
    private val connection: ServerConnectionManager,
    private val repository: SessionRepository,
    private val settings: SettingsRepository,
    private val workbench: Workbench,
    private val scope: CoroutineScope,
) {

    private val finishing = Mutex()

    val tasks: StateFlow<List<BackgroundTask>> by lazy {
        workbench.data.map { it.tasks }.stateIn(scope, SharingStarted.Eagerly, workbench.current.tasks)
    }

    fun start() {
        createChannel()
        scope.launch { connection.events.collect(::onEvent) }
        scope.launch {
            connection.state.collect { state ->
                if (state is ConnectionState.Connected) reconcile()
            }
        }
    }

    fun isTask(sessionId: String): Boolean = workbench.current.tasks.any { it.sessionId == sessionId }

    /** Creates a chat, sends [prompt] and tracks it. Returns the session id. */
    suspend fun launch(
        prompt: String,
        attachments: List<PromptAttachment> = emptyList(),
        model: ModelRef? = null,
        agent: String? = null,
    ): ApiResult<String> {
        if (!connection.isConnected) connection.connect()
        val title = prompt.lineSequence().firstOrNull().orEmpty().trim().take(TITLE_CHARS).ifBlank { null }
        val session = when (val created = repository.createSession(title)) {
            is ApiResult.Ok -> created.value
            is ApiResult.Err -> return created
        }
        val current = settings.current()
        val request = PromptRequest(
            text = prompt,
            model = model ?: current.model,
            agent = (agent ?: current.agent).ifBlank { null },
            system = current.activeSystemPrompt?.text?.takeIf { it.isNotBlank() },
            attachments = attachments,
        )
        val task = BackgroundTask(
            id = Workbench.newId(),
            sessionId = session.id,
            prompt = prompt,
            createdAt = System.currentTimeMillis(),
        )
        workbench.update { data -> data.copy(tasks = (listOf(task) + data.tasks).take(Workbench.MAX_TASKS)) }
        return when (val sent = repository.sendPrompt(session.id, request)) {
            is ApiResult.Ok -> ApiResult.Ok(session.id)
            is ApiResult.Err -> {
                finish(session.id, TaskStatus.FAILED, error = sent.message)
                sent
            }
        }
    }

    suspend fun remove(id: String) {
        workbench.update { data -> data.copy(tasks = data.tasks.filterNot { it.id == id }) }
    }

    suspend fun clearFinished() {
        workbench.update { data -> data.copy(tasks = data.tasks.filter { it.status == TaskStatus.RUNNING }) }
    }

    private fun onEvent(event: OpencodeEvent) {
        val sessionId = when (event) {
            is OpencodeEvent.Idle -> event.sessionId
            is OpencodeEvent.Status -> event.sessionId.takeIf { !event.busy }
            is OpencodeEvent.SessionFailed -> event.sessionId
            else -> null
        } ?: return
        val task = running(sessionId) ?: return
        scope.launch {
            if (event is OpencodeEvent.SessionFailed) {
                finish(task.sessionId, TaskStatus.FAILED, error = event.message)
            } else {
                // Idle can precede the final message write by a moment.
                delay(SETTLE_MS)
                if ((repository.sessionBusy(sessionId) as? ApiResult.Ok)?.value == true) return@launch
                finish(task.sessionId, TaskStatus.DONE)
            }
        }
    }

    private suspend fun reconcile() {
        workbench.current.tasks.filter { it.status == TaskStatus.RUNNING }.forEach { task ->
            when ((repository.sessionBusy(task.sessionId) as? ApiResult.Ok)?.value) {
                false -> finish(task.sessionId, TaskStatus.DONE)
                else -> Unit
            }
        }
    }

    private fun running(sessionId: String) = workbench.current.tasks.firstOrNull { it.sessionId == sessionId && it.status == TaskStatus.RUNNING }

    private suspend fun finish(sessionId: String, status: TaskStatus, error: String? = null) = finishing.withLock {
        val task = running(sessionId) ?: return@withLock
        val messages = (repository.messages(sessionId) as? ApiResult.Ok)?.value.orEmpty()
        val last = messages.lastOrNull { it.role == Role.ASSISTANT }
        val result = last?.plainText?.trim()?.takeIf { it.isNotEmpty() }
        val failure = error ?: last?.error
        val finalStatus = if (status == TaskStatus.DONE && failure != null && result == null) TaskStatus.FAILED else status
        val updated = task.copy(
            status = finalStatus,
            finishedAt = System.currentTimeMillis(),
            result = result?.take(MAX_RESULT_CHARS),
            error = failure,
        )
        workbench.update { data -> data.copy(tasks = data.tasks.map { if (it.id == task.id) updated else it }) }
        notify(updated)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(task: BackgroundTask) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val title = if (task.status == TaskStatus.DONE) {
            context.getString(R.string.task_notify_done, task.prompt.lineSequence().first().take(TITLE_CHARS))
        } else {
            context.getString(R.string.task_notify_failed, task.prompt.lineSequence().first().take(TITLE_CHARS))
        }
        val body = (if (task.status == TaskStatus.DONE) task.result else task.error)
            ?: context.getString(R.string.task_notify_empty)
        val open = PendingIntent.getActivity(
            context,
            task.sessionId.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_SESSION_ID, task.sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(MAX_NOTIFICATION_CHARS)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + (task.id.hashCode() and 0xFFFF), notification) }
    }

    private fun createChannel() {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.task_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        private const val CHANNEL = "opencode_tasks"
        private const val NOTIFICATION_BASE = 7000
        private const val TITLE_CHARS = 60
        private const val SETTLE_MS = 1_500L
        private const val MAX_RESULT_CHARS = 4_000
        private const val MAX_NOTIFICATION_CHARS = 1_500
    }
}
