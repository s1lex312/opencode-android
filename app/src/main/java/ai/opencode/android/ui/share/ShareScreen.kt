package ai.opencode.android.ui.share

import ai.opencode.android.R
import ai.opencode.android.data.model.PromptAttachment
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.share.Draft
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.chat.Attachments
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SwitchRow
import ai.opencode.android.ui.settings.SettingsPage
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareUiState(
    val text: String = "",
    val attachments: List<PromptAttachment> = emptyList(),
    val errors: List<String> = emptyList(),
    val sessions: List<ServerSession> = emptyList(),
    val working: Boolean = false,
)

class ShareViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()

    init {
        val shared = container.pendingShare.value
        container.pendingShare.value = null
        if (shared != null) {
            val text = listOfNotNull(shared.subject?.takeIf { it.isNotBlank() && !shared.text.contains(it) }, shared.text.takeIf { it.isNotBlank() })
                .joinToString("\n\n")
            _state.update { it.copy(text = text) }
            viewModelScope.launch {
                shared.uris.forEach { uri ->
                    when (val result = Attachments.read(container.context, uri)) {
                        is Attachments.Result.Ok -> _state.update { it.copy(attachments = it.attachments + result.attachment) }
                        is Attachments.Result.Err -> _state.update { it.copy(errors = it.errors + result.message.resolve(container.context)) }
                    }
                }
            }
        }
        viewModelScope.launch {
            if (!container.connectionManager.isConnected) container.connectionManager.connect()
            val sessions = (container.sessionRepository.loadSessions() as? ApiResult.Ok)?.value
                ?: container.sessionRepository.cachedSessions()
            _state.update { state -> state.copy(sessions = sessions.filter { it.parentID == null }.sortedByDescending { it.updated }.take(MAX_SESSIONS)) }
        }
    }

    fun setText(text: String) = _state.update { it.copy(text = text) }

    fun removeAttachment(id: String) = _state.update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }

    fun toChat(sessionId: String?, send: Boolean, onOpen: (String) -> Unit, onError: (String) -> Unit) {
        val current = _state.value
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val target = sessionId ?: when (val created = container.sessionRepository.createSession(current.text.lineSequence().firstOrNull()?.take(60))) {
                is ApiResult.Ok -> created.value.id
                is ApiResult.Err -> {
                    _state.update { it.copy(working = false) }
                    onError(created.message)
                    return@launch
                }
            }
            container.draftInbox.put(target, Draft(current.text, current.attachments, send))
            _state.update { it.copy(working = false) }
            onOpen(target)
        }
    }

    fun toBackground(onDone: () -> Unit, onError: (String) -> Unit) {
        val current = _state.value
        if (current.text.isBlank() && current.attachments.isEmpty()) return
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            val prompt = current.text.ifBlank { "Describe the attached file." }
            when (val result = container.backgroundTasks.launch(prompt, current.attachments)) {
                is ApiResult.Ok -> onDone()
                is ApiResult.Err -> onError(result.message)
            }
            _state.update { it.copy(working = false) }
        }
    }

    companion object {
        private const val MAX_SESSIONS = 12

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShareViewModel(container) }
        }
    }
}

/** Target picker for text and images shared from other apps. */
@Composable
fun ShareScreen(container: AppContainer, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val viewModel: ShareViewModel = viewModel(factory = ShareViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sendNow by rememberSaveable { mutableStateOf(true) }
    val error: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    val canSend = !state.working && (state.text.isNotBlank() || state.attachments.isNotEmpty())

    SettingsPage(title = stringResource(R.string.share_title), onBack = onBack) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                label = { Text(stringResource(R.string.share_text)) },
                minLines = 3,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.attachments.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.attachments.forEach { attachment -> SharedThumb(attachment) }
                }
            }
            state.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            SwitchRow(
                title = stringResource(R.string.share_send_now),
                subtitle = stringResource(R.string.share_send_now_hint),
                checked = sendNow,
                onCheckedChange = { sendNow = it },
            )
        }
        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.share_new_chat),
                icon = Icons.Default.AddComment,
                enabled = canSend,
                onClick = { viewModel.toChat(null, sendNow, onOpenSession, error) },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.share_background),
                subtitle = stringResource(R.string.share_background_hint),
                icon = Icons.Default.Schedule,
                enabled = canSend,
                onClick = {
                    viewModel.toBackground(
                        onDone = {
                            Toast.makeText(context, context.getString(R.string.task_started), Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        onError = error,
                    )
                },
            )
        }
        if (state.sessions.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.share_existing)) {
                state.sessions.forEachIndexed { index, session ->
                    if (index > 0) SettingsDivider()
                    SettingsItem(
                        title = session.title.ifBlank { stringResource(R.string.chat_untitled) },
                        subtitle = session.directory,
                        icon = Icons.AutoMirrored.Filled.Chat,
                        enabled = canSend,
                        onClick = { viewModel.toChat(session.id, sendNow, onOpenSession, error) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedThumb(attachment: PromptAttachment) {
    val bitmap = remember(attachment.id) { Attachments.thumbnail(attachment) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = attachment.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(MaterialTheme.shapes.small),
        )
    } else {
        Text(attachment.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp))
    }
}

