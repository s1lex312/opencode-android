package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.Role
import ai.opencode.android.settings.ChatSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource

/** A user prompt in both chat styles and an assistant answer, for the appearance editors. */
@Composable
fun MessagePreview() {
    val userText = stringResource(R.string.settings_preview_user)
    val assistantText = stringResource(R.string.ui_preview_assistant_markdown)
    val user = remember(userText) { ChatMessage("preview-user", Role.USER, parts = listOf(MessagePart.Text("u", userText))) }
    val assistant = remember(assistantText) {
        ChatMessage("preview-assistant", Role.ASSISTANT, parts = listOf(MessagePart.Text("a", assistantText)))
    }
    val chat = remember { ChatSettings(showTimestamps = false) }
    Column {
        MessageItem(message = user, onLongPress = {})
        ClaudeMessage(message = user, chat = chat, onLongPress = {})
        ClaudeMessage(message = assistant, chat = chat, onLongPress = {})
    }
}
