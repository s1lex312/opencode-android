package ai.opencode.android.ui.chat

import ai.opencode.android.ui.theme.csp

import ai.opencode.android.R
import ai.opencode.android.data.model.MentionCandidate
import ai.opencode.android.data.model.PromptAttachment
import ai.opencode.android.settings.ChatStyle
import ai.opencode.android.ui.format.oneLine
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the caret currently sits in: an `@file` mention, a `/command`, or plain text. */
sealed interface ComposerToken {
    data class Mention(val query: String, val start: Int, val end: Int) : ComposerToken
    data class Command(val prefix: String) : ComposerToken
    data object None : ComposerToken
}

fun composerToken(value: TextFieldValue): ComposerToken {
    val text = value.text
    val caret = value.selection.end.coerceIn(0, text.length)
    if (text.startsWith("/") && caret > 0 && text.substring(0, caret).none { it.isWhitespace() }) {
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

/** Input mode signalled by the first character, like Claude Code's `!` bash mode. */
enum class ComposerMode { PROMPT, SHELL, COMMAND }

fun composerMode(text: String): ComposerMode = when {
    text.startsWith("!") -> ComposerMode.SHELL
    text.startsWith("/") -> ComposerMode.COMMAND
    else -> ComposerMode.PROMPT
}

@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    state: ChatUiState,
    commandSuggestions: List<CommandSuggestion>,
    mentionToken: ComposerToken.Mention?,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickModel: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onCycleAgent: () -> Unit,
    onPickSystemPrompt: (String?) -> Unit,
    onManageSystemPrompts: () -> Unit,
    onOpenUsage: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onVoice: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPickMention: (MentionCandidate) -> Unit,
    onPickCommand: (CommandSuggestion) -> Unit,
    onRemoveQueued: (Long) -> Unit,
    onEditQueued: (Long) -> Unit,
) {
    val claude = state.chat.style == ChatStyle.CLAUDE_CODE
    val mode = composerMode(value.text)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp),
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
                mentions = if (mentionToken != null) state.mentionSuggestions else emptyList(),
                commands = commandSuggestions,
                onPickMention = onPickMention,
                onPickCommand = onPickCommand,
            )

            if (!claude) AgentSwitcher(state = state, onSelectAgent = onSelectAgent)

            if (state.attachments.isNotEmpty()) {
                AttachmentRow(state.attachments, onRemoveAttachment)
            }

            val accent = when (mode) {
                ComposerMode.SHELL -> MaterialTheme.colorScheme.tertiary
                ComposerMode.COMMAND -> MaterialTheme.colorScheme.primary
                ComposerMode.PROMPT -> MaterialTheme.colorScheme.outline
            }
            Surface(
                shape = if (claude) RoundedCornerShape(10.dp) else MaterialTheme.shapes.extraLarge,
                color = if (claude) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (claude) BorderStroke(1.dp, accent.copy(alpha = if (mode == ComposerMode.PROMPT) 0.6f else 1f)) else null,
            ) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 4.dp, bottom = 2.dp)) {
                    if (claude) {
                        Text(
                            text = when (mode) {
                                ComposerMode.SHELL -> "!"
                                else -> ">"
                            },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (mode == ComposerMode.PROMPT) MaterialTheme.colorScheme.onSurfaceVariant else accent,
                            modifier = Modifier.padding(start = 12.dp, bottom = 17.dp),
                        )
                    } else {
                        AttachButton(enabled = state.connected, onPickImage = onPickImage, onPickFile = onPickFile)
                    }
                    TextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                text = stringResource(if (mode == ComposerMode.SHELL) R.string.chat_shell_hint else R.string.chat_input_hint_claude),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        maxLines = 8,
                        enabled = state.connected,
                        textStyle = scaled(MaterialTheme.typography.bodyLarge).let {
                            if (claude && (state.chat.monospace || mode != ComposerMode.PROMPT)) it.copy(fontFamily = FontFamily.Monospace) else it
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = if (mode == ComposerMode.PROMPT) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                            autoCorrectEnabled = mode == ComposerMode.PROMPT,
                            imeAction = if (state.chat.sendOnEnter) ImeAction.Send else ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    if (claude) AttachButton(enabled = state.connected, onPickImage = onPickImage, onPickFile = onPickFile)
                    SendButton(value, state, onSend, onStop, onVoice)
                }
            }

            if (ai.opencode.android.ui.theme.LocalUiTokens.current.showStatusLine) StatusLine(
                state = state,
                onCycleAgent = onCycleAgent,
                onSelectAgent = onSelectAgent,
                onPickModel = onPickModel,
                onPickSystemPrompt = onPickSystemPrompt,
                onManageSystemPrompts = onManageSystemPrompts,
                onOpenUsage = onOpenUsage,
            )
        }
    }
}

@Composable
private fun SendButton(value: TextFieldValue, state: ChatUiState, onSend: () -> Unit, onStop: () -> Unit, onVoice: () -> Unit) {
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
                imageVector = if (state.busy && composerMode(value.text) == ComposerMode.PROMPT) Icons.Default.Schedule else Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(if (state.busy) R.string.chat_queue_send else R.string.chat_send),
            )
        }
    }
}

/**
 * The line under the input: `⏵⏵ build · model · prompt` on the left (tap the mode to cycle,
 * long-press for the list) and the context gauge on the right, like Claude Code's footer.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusLine(
    state: ChatUiState,
    onCycleAgent: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onPickModel: () -> Unit,
    onPickSystemPrompt: (String?) -> Unit,
    onManageSystemPrompts: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.csp)
    var agentMenu by remember { mutableStateOf(false) }
    var promptMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val agent = state.effectiveAgent
            val (glyph, color) = when (agent) {
                "plan" -> "⏸" to MaterialTheme.colorScheme.tertiary
                "build" -> "⏵⏵" to MaterialTheme.colorScheme.primary
                else -> "✦" to MaterialTheme.colorScheme.secondary
            }
            Box {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append("$glyph $agent") }
                    },
                    style = style,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .combinedClickable(onClick = onCycleAgent, onLongClick = { agentMenu = true })
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
                DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                    state.primaryAgents.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.name)
                                    option.description?.takeIf { it.isNotBlank() }?.let {
                                        Text(it.oneLine(60), style = MaterialTheme.typography.bodySmall, color = dim)
                                    }
                                }
                            },
                            leadingIcon = { Icon(agentIcon(option.name), contentDescription = null) },
                            onClick = {
                                agentMenu = false
                                onSelectAgent(option.name)
                            },
                        )
                    }
                }
            }
            Separator(style)
            Text(
                text = state.model?.modelID ?: stringResource(R.string.chat_model_default),
                style = style,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 180.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onPickModel)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
            Box {
                val prompt = state.activeSystemPrompt
                if (prompt != null || state.systemPrompts.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Separator(style)
                        Text(
                            text = "§ " + (prompt?.name?.ifBlank { null } ?: stringResource(R.string.chat_system_prompt_none_short)),
                            style = style,
                            color = if (prompt != null) MaterialTheme.colorScheme.secondary else dim,
                            maxLines = 1,
                            modifier = Modifier
                                .widthIn(max = 140.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { promptMenu = true }
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                }
                SystemPromptMenu(state, promptMenu, { promptMenu = false }, onPickSystemPrompt, onManageSystemPrompts)
            }
        }
        ContextGauge(state, onOpenUsage)
    }
}

@Composable
private fun Separator(style: androidx.compose.ui.text.TextStyle) {
    Text(" · ", style = style, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun SystemPromptMenu(
    state: ChatUiState,
    open: Boolean,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    onManage: () -> Unit,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_system_prompt_none)) },
            onClick = {
                onDismiss()
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
                    onDismiss()
                    onPick(prompt.id)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chat_system_prompt_manage)) },
            onClick = {
                onDismiss()
                onManage()
            },
        )
    }
}

/** Ring plus `27% · 54K/200K · $0.31`; colours follow the user's warn/danger thresholds. */
@Composable
private fun ContextGauge(state: ChatUiState, onClick: () -> Unit) {
    val usage = state.usage
    val fraction = usage?.fraction
    val color = gaugeColor(fraction, state.chat.contextWarnPercent, state.chat.contextDangerPercent)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, useCenter = false, style = stroke)
            drawArc(color, -90f, 360f * (fraction ?: 0f), useCenter = false, style = stroke)
        }
        Spacer(Modifier.width(5.dp))
        val label = when {
            usage == null -> stringResource(R.string.usage_context_unknown_short)
            else -> buildString {
                fraction?.let { append((it * 100).toInt()).append("% · ") }
                append(compactCount(usage.used))
                usage.limit?.let { append('/').append(compactCount(it)) }
                if (state.chat.showCost && usage.cost > 0) append(" · ").append(formatCost(usage.cost))
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.csp,
            color = if (fraction != null && fraction * 100 >= state.chat.contextWarnPercent) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
fun gaugeColor(fraction: Float?, warnPercent: Int, dangerPercent: Int): Color = when {
    fraction == null -> MaterialTheme.colorScheme.primary
    fraction * 100 >= dangerPercent -> MaterialTheme.colorScheme.error
    fraction * 100 >= warnPercent -> Color(0xFFE0A030)
    else -> MaterialTheme.colorScheme.primary
}

/** `build` / `plan` / custom primary agents, one tap away from the input (bubble style). */
@Composable
private fun AgentSwitcher(state: ChatUiState, onSelectAgent: (String) -> Unit) {
    val agents = state.primaryAgents
    if (agents.isEmpty()) return
    val current = state.effectiveAgent
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
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
private fun AttachButton(enabled: Boolean, onPickImage: () -> Unit, onPickFile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
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
    mentions: List<MentionCandidate>,
    commands: List<CommandSuggestion>,
    onPickMention: (MentionCandidate) -> Unit,
    onPickCommand: (CommandSuggestion) -> Unit,
) {
    if (mentions.isEmpty() && commands.isEmpty()) return
    val mono = FontFamily.Monospace
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        LazyColumn(Modifier.heightIn(max = 280.dp)) {
            items(commands, key = { "c:" + it.name + it.local }) { command ->
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { onPickCommand(command) }.padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "/" + command.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = mono,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        command.argHint?.let {
                            Text(" $it", style = MaterialTheme.typography.bodySmall, fontFamily = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        commandSourceLabel(command)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                    val description = command.description?.oneLine(90) ?: localCommandDescription(command.name)
                    if (!description.isNullOrBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            items(mentions, key = { "m:" + it.kind + it.value }) { candidate ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPickMention(candidate) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = when (candidate.kind) {
                            MentionCandidate.Kind.AGENT -> Icons.Default.SmartToy
                            MentionCandidate.Kind.DIRECTORY -> Icons.Default.Folder
                            MentionCandidate.Kind.FILE -> Icons.Default.InsertDriveFile
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (candidate.kind == MentionCandidate.Kind.AGENT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = candidate.value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = mono,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (candidate.kind == MentionCandidate.Kind.AGENT) {
                            Text(
                                text = candidate.description?.oneLine(70) ?: stringResource(R.string.chat_mention_agent),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun commandSourceLabel(command: CommandSuggestion): String? = when {
    command.local -> stringResource(R.string.cmd_source_app)
    command.source == "mcp" -> "MCP"
    command.source == "skill" -> stringResource(R.string.cmd_source_skill)
    command.source == ChatViewModel.SNIPPET_SOURCE -> stringResource(R.string.cmd_source_snippet)
    else -> null
}

@Composable
private fun localCommandDescription(name: String): String? =
    ClientCommand.find(name)?.let { stringResource(it.description) }

/** Inserts `@path ` (or `@dir/` to keep browsing) in place of the mention token under the caret. */
fun TextFieldValue.withMention(token: ComposerToken.Mention, value: String, keepOpen: Boolean = false): TextFieldValue {
    val insert = if (keepOpen) "@$value" else "@$value "
    val text = text.substring(0, token.start) + insert + text.substring(token.end)
    return TextFieldValue(text, TextRange(token.start + insert.length))
}

fun commandValue(name: String): TextFieldValue = TextFieldValue("/$name ", TextRange(name.length + 2))
