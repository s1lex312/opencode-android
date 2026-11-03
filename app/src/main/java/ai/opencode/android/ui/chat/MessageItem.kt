package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PermissionRequest
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ToolCall
import ai.opencode.android.data.model.ToolStatus
import ai.opencode.android.ui.format.formatClockTime
import ai.opencode.android.ui.theme.LocalChatTextScale
import ai.opencode.android.ui.theme.StatusColors
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** [style] scaled by Settings → Appearance → Text size. */
@Composable
internal fun scaled(style: TextStyle): TextStyle {
    val scale = LocalChatTextScale.current
    val font = ai.opencode.android.ui.theme.LocalUiTokens.current.chatFont
    val sized = if (scale == 1f) style else style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale)
    return if (font == null) sized else sized.copy(fontFamily = font)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(message: ChatMessage, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    val isUser = message.role == Role.USER
    val context = LocalContext.current
    val copied = stringResource(R.string.copied_to_clipboard)
    val copy: () -> Unit = onLongPress ?: {
        val text = message.plainText
        if (text.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("message", text))
            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        }
    }
    val files = message.parts.filterIsInstance<MessagePart.File>()

    // Notices-only messages (turn finished, compaction) render as a centred chip.
    val notices = message.parts.filterIsInstance<MessagePart.Notice>()
    if (!isUser && notices.isNotEmpty() && notices.size == message.parts.size && message.error == null) {
        Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    text = notices.joinToString(" · ") { it.text },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
        return
    }

    if (isUser) {
        Column(
            modifier = modifier.fillMaxWidth().padding(
                start = 48.dp, end = 12.dp,
                top = ai.opencode.android.ui.theme.LocalUiTokens.current.gap(6f),
                bottom = ai.opencode.android.ui.theme.LocalUiTokens.current.gap(6f),
            ),
            horizontalAlignment = Alignment.End,
        ) {
            val tokens = ai.opencode.android.ui.theme.LocalUiTokens.current
            Surface(
                shape = RoundedCornerShape(
                    topStart = tokens.bubbleRadius, topEnd = tokens.bubbleRadius,
                    bottomStart = tokens.bubbleRadius, bottomEnd = tokens.bubbleRadius / 4,
                ),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = tokens.bubbleMaxWidth).combinedClickable(onClick = {}, onLongClick = copy),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    if (message.plainText.isNotBlank()) {
                        Text(
                            text = message.plainText,
                            style = scaled(MaterialTheme.typography.bodyLarge),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (files.isNotEmpty()) FileChips(files)
                }
            }
            if (message.created > 0) {
                Text(
                    text = formatClockTime(message.created),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp, end = 6.dp),
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = copy)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_assistant_name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val meta = listOfNotNull(message.model, formatClockTime(message.created).takeIf { message.created > 0 })
            if (meta.isNotEmpty()) {
                Text(
                    text = "  " + meta.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        if (message.parts.isEmpty() && message.error == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_thinking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message.parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> MarkdownText(text = part.text, style = scaled(MaterialTheme.typography.bodyLarge))
                is MessagePart.Reasoning -> ReasoningBlock(text = part.text)
                is MessagePart.Tool -> ToolCallCard(call = part.call)
                is MessagePart.Notice -> Text(
                    text = part.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is MessagePart.File -> Unit
            }
        }
        if (files.isNotEmpty()) FileChips(files)
        message.error?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

/** Attached files/images of a message, shown as small labelled chips. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FileChips(files: List<MessagePart.File>) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        files.forEach { file ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (file.mime.startsWith("image/")) Icons.Default.Image else Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_reasoning),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.chat_collapse else R.string.chat_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = text,
                    style = scaled(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/** Expandable card representing one tool invocation, including its raw input and output. */
@Composable
fun ToolCallCard(call: ToolCall, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(call.id) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolStatusIcon(call.status)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = call.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    call.title?.takeIf { it.isNotBlank() }?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.chat_collapse else R.string.chat_expand),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (call.error != null) {
                Text(text = call.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    call.input?.takeIf { it.isNotBlank() }?.let { CodeBlock(label = stringResource(R.string.chat_tool_input), text = it) }
                    call.output?.takeIf { it.isNotBlank() }?.let { CodeBlock(label = stringResource(R.string.chat_tool_output), text = it) }
                    if (call.input.isNullOrBlank() && call.output.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.chat_tool_no_payload),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStatusIcon(status: ToolStatus) {
    val description = stringResource(
        when (status) {
            ToolStatus.PENDING -> R.string.chat_tool_queued
            ToolStatus.RUNNING -> R.string.chat_tool_running
            ToolStatus.COMPLETED -> R.string.chat_tool_done
            ToolStatus.ERROR -> R.string.chat_tool_failed
        },
    )
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        when (status) {
            ToolStatus.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = StatusColors.warn)
            ToolStatus.PENDING -> Icon(Icons.Default.Schedule, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            ToolStatus.COMPLETED -> Icon(Icons.Default.CheckCircle, contentDescription = description, tint = StatusColors.ok, modifier = Modifier.size(18.dp))
            ToolStatus.ERROR -> Icon(Icons.Default.ErrorOutline, contentDescription = description, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CodeBlock(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
                .horizontalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(text = text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Approval card shown above the composer for `permission.asked` events. */
@Composable
fun PermissionPrompt(
    request: PermissionRequest,
    onDecision: (PermissionDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.chat_permission_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(request.title, style = MaterialTheme.typography.bodyMedium)
            if (request.resources.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    request.resources.take(6).forEach { resource ->
                        Text(
                            text = "• $resource",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (request.resources.size > 6) {
                        Text(
                            text = stringResource(R.string.chat_permission_more, request.resources.size - 6),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onDecision(PermissionDecision.REJECT) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_permission_deny))
                }
                Button(onClick = { onDecision(PermissionDecision.ONCE) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.chat_permission_allow))
                }
            }
            TextButton(onClick = { onDecision(PermissionDecision.ALWAYS) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_permission_always))
            }
        }
    }
}
