package ai.opencode.android.ui.terminal

import ai.opencode.android.ui.theme.csp

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.ErrorCard
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private val TerminalBackground = Color(0xFF0E1116)
private val TerminalForeground = Color(0xFFD6DEEB)
private val CursorColor = Color(0xFF7AA2F7)

/** xterm's 16 base colours, tuned for the dark background. */
private val Palette16 = listOf(
    0xFF1D2128, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFD6DEEB,
    0xFF5C6370, 0xFFFF7A85, 0xFFB5E890, 0xFFFFD68A, 0xFF82C4FF, 0xFFDDA0F0, 0xFF7FD6E0, 0xFFFFFFFF,
).map { Color(it) }

private fun colorOf(index: Int, fallback: Color): Color = when {
    index == TerminalBuffer.DEFAULT -> fallback
    index and TerminalBuffer.TRUECOLOR != 0 -> Color(0xFF000000 or (index and 0xFFFFFF).toLong())
    index < 16 -> Palette16[index]
    index < 232 -> {
        val n = index - 16
        val steps = listOf(0, 95, 135, 175, 215, 255)
        Color(steps[n / 36], steps[(n / 6) % 6], steps[n % 6])
    }
    else -> (8 + (index - 232) * 10).let { Color(it, it, it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(container: AppContainer, cwd: String?, onBack: () -> Unit) {
    val viewModel: TerminalViewModel = viewModel(key = "terminal-$cwd", factory = TerminalViewModel.factory(container, cwd))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var line by rememberSaveable { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val buffer = viewModel.buffer

    LaunchedEffect(state.version) {
        val target = (buffer.lineCount - 1).coerceAtLeast(0)
        listState.scrollToItem(target)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.terminal_title))
                        Text(
                            text = when (state.status) {
                                TerminalUiState.Status.CONNECTING -> stringResource(R.string.terminal_connecting)
                                TerminalUiState.Status.OPEN -> cwd ?: stringResource(R.string.terminal_connected)
                                TerminalUiState.Status.CLOSED -> stringResource(R.string.terminal_closed)
                                TerminalUiState.Status.FAILED -> stringResource(R.string.terminal_failed)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { copy(context, buffer.text()) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.terminal_copy))
                    }
                    IconButton(onClick = viewModel::open) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.terminal_reconnect))
                    }
                },
            )
        },
        containerColor = TerminalBackground,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            state.error?.let { ErrorCard(message = it, onRetry = viewModel::open) }

            val fontSize = 12.sp
            val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, lineHeight = 16.csp, color = TerminalForeground)
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(TerminalBackground).padding(horizontal = 6.dp)) {
                val cell = remember(textStyle) { measurer.measure("W", textStyle).size }
                val cols = with(density) { (maxWidth.toPx() / cell.width.coerceAtLeast(1)).toInt() }.coerceIn(20, 300)
                val rows = with(density) { (maxHeight.toPx() / 16.sp.toPx()).toInt() }.coerceIn(5, 200)
                LaunchedEffect(cols, rows) { viewModel.resize(cols, rows) }

                // Reading the version makes each line recompose when output arrives.
                val version = state.version
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(count = buffer.lineCount, key = null) { index ->
                        Text(text = renderLine(buffer, index, version), style = textStyle, softWrap = false, maxLines = 1)
                    }
                }
            }

            ExtraKeys(
                ctrl = ctrl,
                onCtrl = { ctrl = !ctrl },
                onKey = { sequence ->
                    viewModel.send(sequence)
                    ctrl = false
                },
            )

            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                    OutlinedTextField(
                        value = line,
                        onValueChange = { value ->
                            if (ctrl && value.length == line.length + 1) {
                                // Ctrl+<letter>: send the control code instead of typing the letter.
                                val ch = value.last().uppercaseChar()
                                if (ch in '@'..'_') viewModel.send((ch.code - 64).toChar().toString())
                                ctrl = false
                            } else {
                                line = value
                            }
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.terminal_input_hint), fontFamily = FontFamily.Monospace) },
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.csp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false,
                            imeAction = ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = {
                            viewModel.send(line + "\r")
                            line = ""
                        }),
                    )
                    IconButton(
                        onClick = {
                            viewModel.send(line + "\r")
                            line = ""
                        },
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = stringResource(R.string.terminal_enter))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtraKeys(ctrl: Boolean, onCtrl: () -> Unit, onKey: (String) -> Unit) {
    val keys = listOf(
        "Esc" to "\u001b",
        "Tab" to "\t",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "←" to "\u001b[D",
        "→" to "\u001b[C",
        "^C" to "\u0003",
        "^D" to "\u0004",
        "^L" to "\u000c",
        "^Z" to "\u001a",
        "|" to "|",
        "~" to "~",
        "/" to "/",
        "-" to "-",
    )
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilledTonalButton(
            onClick = onCtrl,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            colors = if (ctrl) {
                androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            } else {
                androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()
            },
        ) { Text("Ctrl", fontFamily = FontFamily.Monospace) }
        keys.forEach { (label, sequence) ->
            FilledTonalButton(
                onClick = { onKey(sequence) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            ) { Text(label, fontFamily = FontFamily.Monospace) }
        }
    }
}

private fun renderLine(buffer: TerminalBuffer, index: Int, @Suppress("UNUSED_PARAMETER") version: Long): AnnotatedString {
    val cells = buffer.line(index)
    val cursorCol = if (index == buffer.cursorLine) buffer.cursorCol else -1
    var end = cells.size
    while (end > 0 && cells[end - 1].char == ' ' && cells[end - 1].style.bg == TerminalBuffer.DEFAULT && end - 1 != cursorCol) end--
    return buildAnnotatedString {
        for (col in 0 until maxOf(end, cursorCol + 1).coerceAtMost(cells.size)) {
            val cell = cells[col]
            var fg = colorOf(cell.style.fg, TerminalForeground)
            var bg = colorOf(cell.style.bg, Color.Transparent)
            if (cell.style.inverse) {
                val swap = fg
                fg = if (bg == Color.Transparent) TerminalBackground else bg
                bg = swap
            }
            if (col == cursorCol) {
                bg = CursorColor
                fg = TerminalBackground
            }
            withStyle(SpanStyle(color = fg, background = bg, fontWeight = if (cell.style.bold) FontWeight.Bold else null)) {
                append(cell.char)
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
}
