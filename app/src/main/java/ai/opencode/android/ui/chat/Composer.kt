package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.data.model.PromptAttachment
import ai.opencode.android.ui.format.oneLine
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the caret currently sits in: an `@file` mention, a `/command`, or plain text. */
sealed interface ComposerToken {
    data class Mention(val query: String, val start: Int, val end: Int) : ComposerToken
    data class Command(val prefix: String) : ComposerToken
    data object None : ComposerToken
}

fun composerToken(value: TextFieldValue): ComposerToken {
    val text = value.text
    val caret = value.selection.end.coerceIn(0, text.length)
    if (text.startsWith("/") && ' ' !in text.substring(0, caret) && caret > 0) {
        return ComposerToken.Command(text.substring(1, caret))
    }
    var start = caret
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    val word = text.substring(start, caret)
    if (word.startsWith("@") && (start == 0 || text[start - 1].isWhitespace())) {
        return ComposerToken.Mention(word.drop(1), start, caret)
    }
    return ComposerToken.None
}

@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    state: ChatUiState,
    commandSuggestions: List<CommandSuggestion>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickModel: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onPickSystemPrompt: (String?) -> Unit,
    onManageSystemPrompts: () -> Unit,
    onOpenUsage: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onVoice: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPickMention: (String) -> Unit,
    onPickCommand: (String) -> Unit,
    onRemoveQueued: (Long) -> Unit,
    onEditQueued: (Long) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        ) {
            if (!state.connected) {
                Text(
                    text = stringResource(R.string.chat_waiting_server),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
            }

            QueueRow(state, onRemoveQueued, onEditQueued)

            Suggestions(
                files = state.fileSuggestions,
                commands = commandSuggestions,
                onPickMention = onPickMention,
                onPickCommand = onPickCommand,
            )

            AgentSwitcher(state = state, onSelectAgent = onSelectAgent)

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = onPickModel,
                    label = {
                        Text(
                            text = state.model?.modelID ?: stringResource(R.string.chat_model_default),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    shape = CircleShape,
                )
                SystemPromptChip(state, onPickSystemPrompt, onManageSystemPrompts)
                ContextMeter(usage = state.usage, onClick = onOpenUsage)
            }

            if (state.attachments.isNotEmpty()) {
                AttachmentRow(state.attachments, onRemoveAttachment)
            }

            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)) {
                    AttachButton(enabled = state.connected, onPickImage = onPickImage, onPickFile = onPickFile)
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                        maxLines = 6,
                        enabled = state.connected,
                        textStyle = scaled(MaterialTheme.typography.bodyLarge),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    val hasContent = value.text.isNotBlank() || state.attachments.isNotEmpty()
                    when {
                        state.busy && !hasContent -> FilledIconButton(
                            onClick = onStop,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.action_stop))
                        }

                        !hasContent -> IconButton(onClick = onVoice, enabled = state.connected) {
                            Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.chat_voice))
                        }

                        else -> FilledIconButton(onClick = onSend, enabled = state.connected) {
                            Icon(
                                imageVector = if (state.busy) Icons.Default.Schedule else Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(if (state.busy) R.string.chat_queue_send else R.string.chat_send),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `build` / `plan` / custom primary agents, one tap away from the input. */
@Composable
private fun AgentSwitcher(state: ChatUiState, onSelectAgent: (String) -> Unit) {
    val agents = state.primaryAgents
    if (agents.isEmpty()) return
    val current = state.agent.ifBlank { agents.firstOrNull { it.name == "build" }?.name ?: agents.first().name }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        agents.forEach { agent ->
            val selected = agent.name == current
            FilterChip(
                selected = selected,
                onClick = { onSelectAgent(agent.name) },
                label = { Text(agent.name.replaceFirstChar { it.uppercase() }) },
                leadingIcon = if (selected) {
                    { Icon(agentIcon(agent.name), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                shape = CircleShape,
            )
        }
    }
}

private fun agentIcon(name: String) = when (name) {
    "plan" -> Icons.Default.Psychology
    "build" -> Icons.Default.Terminal
    else -> Icons.Default.AutoAwesome
}

@Composable
private fun SystemPromptChip(
    state: ChatUiState,
    onPick: (String?) -> Unit,
    onManage: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = {
                Text(
                    text = state.activeSystemPrompt?.name?.ifBlank { null } ?: stringResource(R.string.chat_system_prompt_none),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
            },
            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
            shape = CircleShape,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_system_prompt_none)) },
                onClick = {
                    open = false
                    onPick(null)
                },
            )
            state.systemPrompts.forEach { prompt ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(prompt.name.ifBlank { stringResource(R.string.system_prompt_untitled) })
                            Text(
                                text = prompt.text.oneLine(60),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onPick(prompt.id)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_system_prompt_manage)) },
                onClick = {
                    open = false
                    onManage()
                },
            )
        }
    }
}

@Composable
private fun AttachButton(enabled: Boolean, onPickImage: () -> Unit, onPickFile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_attach))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_image)) },
                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                onClick = {
                    open = false
                    onPickImage()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_attach_file)) },
                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                onClick = {
                    open = false
                    onPickFile()
                },
            )
        }
    }
}

@Composable
private fun AttachmentRow(attachments: List<PromptAttachment>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            val thumbnail = remember(attachment.id) { Attachments.thumbnail(attachment)?.asImageBitmap() }
            Box {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(64.dp),
                ) {
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail,
                            contentDescription = attachment.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(22.dp))
                            Text(
                                text = attachment.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clickable { onRemove(attachment.id) },
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_remove),
                        tint = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(state: ChatUiState, onRemove: (Long) -> Unit, onEdit: (Long) -> Unit) {
    if (state.queue.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(
            text = stringResource(R.string.chat_queue_title, state.queue.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.queue.forEachIndexed { index, queued ->
                InputChip(
                    selected = false,
                    onClick = { onEdit(queued.id) },
                    label = {
                        Text(
                            text = "${index + 1}. " + queued.text.oneLine(40).ifBlank { stringResource(R.string.chat_attachment_only) },
                            maxLines = 1,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_remove),
                            modifier = Modifier.size(18.dp).clickable { onRemove(queued.id) },
                        )
                    },
                    shape = CircleShape,
                )
            }
        }
    }
}

@Composable
private fun Suggestions(
    files: List<String>,
    commands: List<CommandSuggestion>,
    onPickMention: (String) -> Unit,
    onPickCommand: (String) -> Unit,
) {
    if (files.isEmpty() && commands.isEmpty()) return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
            items(commands, key = { "c:" + it.name }) { command ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPickCommand(command.name) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("/" + command.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = command.description?.oneLine(60) ?: localCommandDescription(command.name).orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            items(files, key = { "f:$it" }) { path ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPickMention(path) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun localCommandDescription(name: String): String? = when (name) {
    ChatViewModel.CMD_COMPACT -> stringResource(R.string.cmd_compact)
    ChatViewModel.CMD_UNDO -> stringResource(R.string.cmd_undo)
    ChatViewModel.CMD_REDO -> stringResource(R.string.cmd_redo)
    ChatViewModel.CMD_FORK -> stringResource(R.string.cmd_fork)
    ChatViewModel.CMD_SHARE -> stringResource(R.string.cmd_share)
    else -> null
}

/** Inserts `@path ` in place of the mention token under the caret. */
fun TextFieldValue.withMention(token: ComposerToken.Mention, path: String): TextFieldValue {
    val insert = "@$path "
    val text = text.substring(0, token.start) + insert + text.substring(token.end)
    return TextFieldValue(text, TextRange(token.start + insert.length))
}

fun commandValue(name: String): TextFieldValue = TextFieldValue("/$name ", TextRange(name.length + 2))
