package ai.opencode.android.ui.chat

import ai.opencode.android.ui.theme.csp

import ai.opencode.android.R
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.MessagePart
import ai.opencode.android.data.model.PermissionDecision
import ai.opencode.android.data.model.PermissionRequest
import ai.opencode.android.data.model.Role
import ai.opencode.android.data.model.ToolCall
import ai.opencode.android.data.model.ToolStatus
import ai.opencode.android.data.remote.OpencodeJson
import ai.opencode.android.data.remote.objects
import ai.opencode.android.data.remote.str
import ai.opencode.android.settings.ChatSettings
import ai.opencode.android.ui.format.formatClockTime
import ai.opencode.android.ui.theme.StatusColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Glyphs of Claude Code's transcript. */
private object Glyph {
    const val PROMPT = "›" // ›
    const val DOT = "●" // ●
    const val ELBOW = "⎿" // ⎿
    const val NOTE = "※" // ※
    val SPINNER = listOf("·", "✢", "✳", "✶", "✻", "✽") // · ✢ ✳ ✶ ✻ ✽
}

/**
 * One message in the Claude Code look: a `›` prompt block for the user, `●` lines for the
 * assistant's text and tools, `⎿` previews under tool calls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClaudeMessage(
    message: ChatMessage,
    chat: ChatSettings,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = FontFamily.Monospace
    val tokens = ai.opencode.android.ui.theme.LocalUiTokens.current
    if (message.role == Role.USER) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(tokens.bubbleRadius / 4),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = tokens.gap(6f))
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row {
                    Text(
                        text = Glyph.PROMPT,
                        fontFamily = mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = scaled(MaterialTheme.typography.bodyLarge),
                        modifier = Modifier.width(18.dp),
                    )
                    Text(
                        text = message.plainText,
                        style = scaled(MaterialTheme.typography.bodyLarge).let { if (chat.monospace) it.copy(fontFamily = mono) else it },
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                val files = message.parts.filterIsInstance<MessagePart.File>()
                if (files.isNotEmpty()) {
                    Text(
                        text = files.joinToString("  ") { "⧉ " + it.name },
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 18.dp, top = 4.dp),
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(start = 12.dp, end = 14.dp, top = tokens.gap(4f), bottom = tokens.gap(4f)),
        verticalArrangement = Arrangement.spacedBy(tokens.gap(8f)),
    ) {
        message.parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> if (part.text.isNotBlank()) {
                    Bulleted(color = MaterialTheme.colorScheme.onSurface) {
                        MarkdownText(text = part.text, style = scaled(MaterialTheme.typography.bodyLarge), monospace = chat.monospace)
                    }
                }

                is MessagePart.Reasoning -> if (part.text.isNotBlank()) ThinkingLine(part.text, chat.expandReasoning)

                is MessagePart.Tool -> ToolLine(part.call, chat)

                is MessagePart.Notice -> Text(
                    text = "${Glyph.NOTE} ${part.text}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp),
                )

                is MessagePart.File -> Text(
                    text = "⧉ ${part.name}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp),
                )
            }
        }
        message.error?.let { error ->
            Elbow(text = error, color = MaterialTheme.colorScheme.error, maxLines = 8)
        }
        if (chat.showTimestamps && message.created > 0 && message.parts.isNotEmpty()) {
            val meta = listOfNotNull(
                message.model,
                formatClockTime(message.created),
                message.cost?.takeIf { chat.showCost && it > 0 }?.let(::formatCost),
            ).joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 18.dp),
            )
        }
    }
}

/** `●` in the gutter, [content] beside it. */
@Composable
private fun Bulleted(color: Color, dotAlpha: Float = 1f, content: @Composable () -> Unit) {
    Row {
        Text(
            text = Glyph.DOT,
            color = color,
            fontSize = 11.sp,
            modifier = Modifier.width(18.dp).padding(top = 5.dp).alpha(dotAlpha),
        )
        Column(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Elbow(text: String, color: Color, maxLines: Int, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 18.dp)) {
        Text(
            text = Glyph.ELBOW + "  ",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.csp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.csp,
            lineHeight = 17.csp,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ThinkingLine(text: String, expandedByDefault: Boolean) {
    var expanded by rememberSaveable(text.hashCode()) { mutableStateOf(expandedByDefault) }
    Column(Modifier.clickable { expanded = !expanded }) {
        Row {
            Text(
                text = "✻",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                modifier = Modifier.width(18.dp),
            )
            Text(
                text = stringResource(if (expanded) R.string.chat_thinking_hide else R.string.chat_thinking_show),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(expanded) {
            Text(
                text = text,
                style = scaled(MaterialTheme.typography.bodyMedium),
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, top = 4.dp),
            )
        }
    }
}

/** What a tool call shows in its one-line header: `Bash(npm test)`, `Read(src/App.kt)`. */
data class ToolHeader(val title: String, val argument: String?)

/** Display names and the most telling argument per opencode tool. */
fun toolHeader(call: ToolCall, input: JsonObject?): ToolHeader {
    fun arg(vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> input?.str(key)?.takeIf { it.isNotBlank() } }
    val name = call.name.lowercase()
    return when {
        name == "bash" -> ToolHeader("Bash", arg("command")?.lineSequence()?.firstOrNull())
        name == "read" -> ToolHeader("Read", arg("filePath", "file_path", "path"))
        name == "write" -> ToolHeader("Write", arg("filePath", "file_path", "path"))
        name == "edit" || name == "multiedit" || name == "patch" || name == "apply_patch" ->
            ToolHeader("Update", arg("filePath", "file_path", "path") ?: call.title)
        name == "glob" -> ToolHeader("Search", arg("pattern"))
        name == "grep" -> ToolHeader("Search", arg("pattern", "query"))
        name == "list" || name == "ls" -> ToolHeader("List", arg("path") ?: ".")
        name == "webfetch" -> ToolHeader("Fetch", arg("url"))
        name == "websearch" || name == "codesearch" -> ToolHeader("Web Search", arg("query"))
        name == "task" -> ToolHeader("Task", arg("description") ?: arg("subagent_type"))
        name == "todowrite" -> ToolHeader("Update Todos", null)
        name == "todoread" -> ToolHeader("Read Todos", null)
        name == "skill" -> ToolHeader("Skill", arg("name"))
        name == "question" -> ToolHeader("Question", arg("question"))
        name.contains("_") -> {
            // MCP tools are `server_tool`.
            val server = call.name.substringBefore('_')
            val tool = call.name.substringAfter('_')
            ToolHeader("$server · $tool", input?.entries?.firstOrNull()?.value?.let { (it as? JsonPrimitive)?.content })
        }
        else -> ToolHeader(call.name.replaceFirstChar { it.uppercase() }, call.title)
    }
}

private fun parseInput(raw: String?): JsonObject? =
    raw?.let { runCatching { OpencodeJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }

@Composable
private fun statusColor(status: ToolStatus): Color = when (status) {
    ToolStatus.COMPLETED -> StatusColors.ok
    ToolStatus.ERROR -> MaterialTheme.colorScheme.error
    ToolStatus.RUNNING -> StatusColors.warn
    ToolStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun ToolLine(call: ToolCall, chat: ChatSettings) {
    val input = remember(call.input) { parseInput(call.input) }
    val header = remember(call.name, input, call.title) { toolHeader(call, input) }
    var expanded by rememberSaveable(call.id) { mutableStateOf(chat.expandTools) }
    val blink = if (call.status == ToolStatus.RUNNING) {
        val transition = rememberInfiniteTransition(label = "tool")
        transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "tool-dot").value
    } else {
        1f
    }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Bulleted(color = statusColor(call.status), dotAlpha = blink) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(header.title) }
                    header.argument?.let { argument ->
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append("(")
                            append(argument.take(160))
                            append(")")
                        }
                    }
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 13.5.csp,
                lineHeight = 19.csp,
                maxLines = if (expanded) 6 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (call.name.equals("todowrite", ignoreCase = true) || call.name.equals("todoread", ignoreCase = true)) {
            TodoList(input ?: parseInput(call.output))
            return@Column
        }

        val preview = when {
            call.status == ToolStatus.ERROR -> call.error ?: call.output
            call.status == ToolStatus.PENDING -> null
            call.status == ToolStatus.RUNNING -> stringResource(R.string.chat_tool_running) + "…"
            else -> call.output?.takeIf { it.isNotBlank() } ?: call.title
        }
        if (!expanded && preview != null && chat.toolPreviewLines > 0) {
            val lines = preview.trimEnd().lines()
            val shown = lines.take(chat.toolPreviewLines).joinToString("\n")
            val more = lines.size - chat.toolPreviewLines
            Elbow(
                text = if (more > 0) shown + "\n" + stringResource(R.string.chat_tool_more_lines, more) else shown,
                color = if (call.status == ToolStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = chat.toolPreviewLines + 1,
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 18.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                call.input?.takeIf { it.isNotBlank() && it != "{}" }?.let { ToolBlock(stringResource(R.string.chat_tool_input), it) }
                (call.error ?: call.output)?.takeIf { it.isNotBlank() }?.let { ToolBlock(stringResource(R.string.chat_tool_output), it) }
            }
        }
    }
}

@Composable
private fun ToolBlock(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        ) {
            Text(
                text = text.take(MAX_TOOL_TEXT),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.csp,
                lineHeight = 16.csp,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
            )
        }
    }
}

private const val MAX_TOOL_TEXT = 20_000

/** `todowrite` input `{todos: [{content, status, priority}]}` as a checklist. */
@Composable
private fun TodoList(input: JsonObject?) {
    val todos = remember(input) {
        val array = input?.get("todos") as? kotlinx.serialization.json.JsonArray
        array?.objects()?.mapNotNull { todo ->
            val content = todo.str("content") ?: return@mapNotNull null
            content to (todo.str("status") ?: "pending")
        }.orEmpty()
    }
    if (todos.isEmpty()) return
    Column(Modifier.padding(start = 18.dp, top = 2.dp)) {
        todos.forEachIndexed { index, (content, status) ->
            Row {
                Text(
                    text = if (index == 0) Glyph.ELBOW + "  " else "   ",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.csp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val (box, color, weight, decoration) = when (status) {
                    "completed" -> Quad("☒", MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Normal, TextDecoration.LineThrough)
                    "in_progress" -> Quad("☐", MaterialTheme.colorScheme.primary, FontWeight.Bold, TextDecoration.None)
                    "cancelled" -> Quad("☒", MaterialTheme.colorScheme.onSurfaceVariant, FontWeight.Normal, TextDecoration.LineThrough)
                    else -> Quad("☐", MaterialTheme.colorScheme.onSurface, FontWeight.Normal, TextDecoration.None)
                }
                Text(
                    text = "$box $content",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.csp,
                    lineHeight = 18.csp,
                    color = color,
                    fontWeight = weight,
                    textDecoration = decoration,
                )
            }
        }
    }
}

private data class Quad(val box: String, val color: Color, val weight: FontWeight, val decoration: TextDecoration)

/**
 * `✻ Pondering… (12s · ↓ 1.2K tokens)` while the agent works. The glyph cycles like Claude
 * Code's spinner; verbs rotate when the playful spinner is on.
 */
@Composable
fun ClaudeSpinner(
    startedAt: Long?,
    outputTokens: Long,
    retry: String?,
    playful: Boolean,
    compacting: Boolean,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SPINNER_TICK_MS)
            now = System.currentTimeMillis()
        }
    }
    val verbs = stringArrayResource(R.array.spinner_verbs)
    val elapsed = startedAt?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0
    val frame = ((now / SPINNER_TICK_MS) % Glyph.SPINNER.size).toInt()
    val verb = when {
        compacting -> stringResource(R.string.usage_compacting).trimEnd('…', '.')
        playful && verbs.isNotEmpty() -> verbs[((startedAt ?: 0L) / 1000 + elapsed / VERB_PERIOD_S).mod(verbs.size.toLong()).toInt()]
        else -> stringResource(R.string.chat_working).trimEnd('…', '.')
    }
    val details = buildList {
        add(formatElapsed(elapsed))
        if (outputTokens > 0) add("↓ " + compactCount(outputTokens) + " " + stringResource(R.string.chat_tokens_short))
        add(stringResource(R.string.chat_tap_to_interrupt))
    }.joinToString(" · ")
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Glyph.SPINNER[frame],
                color = MaterialTheme.colorScheme.primary,
                fontSize = 15.sp,
                modifier = Modifier.width(18.dp),
            )
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)) { append("$verb…") }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" ($details)") }
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 13.csp,
                maxLines = 2,
            )
        }
        retry?.let {
            Elbow(text = it, color = StatusColors.warn, maxLines = 2)
        }
    }
}

private const val SPINNER_TICK_MS = 120L
private const val VERB_PERIOD_S = 4L

fun formatElapsed(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

/**
 * Claude Code's permission dialog: a bordered box with numbered answers instead of buttons.
 */
@Composable
fun ClaudePermissionPrompt(request: PermissionRequest, onDecision: (PermissionDecision) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, accent),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = request.action.ifBlank { stringResource(R.string.chat_permission_title) },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = accent,
                fontSize = 14.csp,
            )
            Text(request.title, style = MaterialTheme.typography.bodyMedium)
            request.resources.take(4).forEach { resource ->
                Text(
                    text = resource,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.csp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            if (request.resources.size > 4) {
                Text(
                    text = LocalContext.current.resources.getQuantityString(R.plurals.chat_permission_more_plural, request.resources.size - 4, request.resources.size - 4),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.chat_permission_question),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            PermissionOption(1, stringResource(R.string.chat_permission_yes)) { onDecision(PermissionDecision.ONCE) }
            PermissionOption(2, stringResource(R.string.chat_permission_yes_always)) { onDecision(PermissionDecision.ALWAYS) }
            PermissionOption(3, stringResource(R.string.chat_permission_no)) { onDecision(PermissionDecision.REJECT) }
        }
    }
}

@Composable
private fun PermissionOption(number: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$number.",
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
