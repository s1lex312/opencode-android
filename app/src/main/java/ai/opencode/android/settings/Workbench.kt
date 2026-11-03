package ai.opencode.android.settings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** When a [Hook] runs, mirroring Claude Code's hook events. */
@Serializable
enum class HookEvent {
    /** Before a prompt is sent. Exit 2 blocks it; stdout can be added to the prompt. */
    @SerialName("prompt_submit") PROMPT_SUBMIT,

    /** When the agent asks for a permission: exit 0 allows once, exit 2 denies, anything else asks you. */
    @SerialName("before_tool") BEFORE_TOOL,

    /** After the agent edited or wrote a file; `$FILE` is its path. */
    @SerialName("file_edited") FILE_EDITED,

    /** When the agent finishes a turn. */
    @SerialName("turn_end") TURN_END,
}

@Serializable
data class Hook(
    val id: String,
    val name: String,
    val event: HookEvent,
    /** Shell command, run in the chat's project directory. */
    val command: String,
    /**
     * Filter: a glob on the file path for [HookEvent.FILE_EDITED] (for example `*.kt`), a regex on the
     * permission type for [HookEvent.BEFORE_TOOL] (`edit|bash`). Blank matches everything.
     */
    val matcher: String = "",
    val enabled: Boolean = true,
    val timeoutSeconds: Int = 60,
    /**
     * Report a failure back to the agent as a new prompt, so it fixes what the hook found (lint
     * errors, failing tests). For [HookEvent.PROMPT_SUBMIT]: append the hook's output to the prompt.
     */
    val feedback: Boolean = false,
)

/** A saved prompt, run as `/name args`. */
@Serializable
data class Snippet(
    val id: String,
    val name: String,
    val body: String,
    val description: String = "",
    /** Send right away; `false` puts the expanded text into the composer for editing. */
    val send: Boolean = true,
)

@Serializable
data class BudgetSettings(
    /** USD per day, 0 = no limit. */
    val daily: Double = 0.0,
    /** USD per calendar month, 0 = no limit. */
    val monthly: Double = 0.0,
    /** Warn once this share of a limit is spent. */
    val warnPercent: Int = 80,
    /** Refuse to send prompts while a limit is exceeded. */
    val block: Boolean = false,
)

@Serializable
enum class TaskStatus { RUNNING, DONE, FAILED }

/** A prompt started from outside a chat; the app notifies with the result. */
@Serializable
data class BackgroundTask(
    val id: String,
    val sessionId: String,
    val prompt: String,
    val createdAt: Long,
    val status: TaskStatus = TaskStatus.RUNNING,
    val finishedAt: Long? = null,
    val result: String? = null,
    val error: String? = null,
)

@Serializable
data class WorkbenchData(
    val hooks: List<Hook> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val budget: BudgetSettings = BudgetSettings(),
    val tasks: List<BackgroundTask> = emptyList(),
)

/**
 * Structured, device-local data that does not fit flat preferences: hooks, snippets, the spending
 * budget and background tasks. One small JSON file, written atomically.
 */
class Workbench(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    private val mutex = Mutex()
    private val _data = MutableStateFlow(load())
    val data: StateFlow<WorkbenchData> = _data.asStateFlow()

    val current: WorkbenchData get() = _data.value

    suspend fun update(transform: (WorkbenchData) -> WorkbenchData) {
        mutex.withLock {
            val updated = transform(_data.value)
            if (updated == _data.value) return
            _data.value = updated
            withContext(Dispatchers.IO) { save(updated) }
        }
    }

    private fun load(): WorkbenchData = runCatching {
        if (file.exists()) JSON.decodeFromString(WorkbenchData.serializer(), file.readText()) else WorkbenchData()
    }.getOrDefault(WorkbenchData())

    private fun save(value: WorkbenchData) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(JSON.encodeToString(WorkbenchData.serializer(), value))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        const val FILE_NAME = "workbench.json"
        const val MAX_TASKS = 50

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
        }

        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
