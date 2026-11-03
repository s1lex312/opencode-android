package ai.opencode.android.ui.diff

import ai.opencode.android.ui.theme.csp

import androidx.compose.ui.res.stringResource
import ai.opencode.android.R
import ai.opencode.android.data.diff.DiffFile
import ai.opencode.android.data.diff.DiffHunk
import ai.opencode.android.data.diff.DiffLineKind
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.AdaptiveContent
import ai.opencode.android.ui.components.EmptyState
import ai.opencode.android.ui.components.ErrorCard
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.theme.diffPalette
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffScreen(container: AppContainer, sessionId: String, onBack: () -> Unit) {
    val viewModel: DiffViewModel = viewModel(
        key = "diff-$sessionId",
        factory = DiffViewModel.factory(container, sessionId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.diff_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (state.files.isEmpty()) {
                                stringResource(R.string.diff_subtitle_empty)
                            } else {
                                androidx.compose.ui.platform.LocalContext.current.resources
                                    .getQuantityString(R.plurals.diff_files_changed, state.files.size, state.files.size)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.diff_reload))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AdaptiveContent(maxWidth = 1100.dp) {
                when {
                    state.loading && state.files.isEmpty() -> LoadingIndicator(stringResource(R.string.diff_loading))
                    state.error != null && state.files.isEmpty() -> ErrorCard(
                        message = state.error.orEmpty(),
                        onRetry = viewModel::load,
                    )

                    state.files.isEmpty() -> EmptyState(
                        title = stringResource(R.string.diff_empty_title),
                        message = stringResource(R.string.diff_empty_body),
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.files, key = { it.path }) { file ->
                            DiffFileCard(file = file)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffFileCard(file: DiffFileView) {
    var expanded by rememberSaveable(file.path) { mutableStateOf(true) }
    val palette = diffPalette()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = file.path,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                file.status?.let { status ->
                    StatusChip(status)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "+${file.additionCount}",
                    color = palette.addedText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "−${file.deletionCount}",
                    color = palette.removedText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.chat_collapse else R.string.chat_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth()) {
                    if (file.parsed.isBinary) {
                        Text(
                            text = stringResource(R.string.diff_binary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    if (file.parsed.hunks.isEmpty() && !file.parsed.isBinary) {
                        Text(
                            text = if (file.rawPatch.isBlank()) {
                                stringResource(R.string.diff_no_patch)
                            } else {
                                stringResource(R.string.diff_unparsed)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                        if (file.rawPatch.isNotBlank()) {
                            RawPatch(file.rawPatch)
                        }
                    }
                    file.parsed.hunks.forEach { hunk ->
                        HunkView(hunk = hunk)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val palette = diffPalette()
    val (label, color) = when (status.lowercase()) {
        "added" -> stringResource(R.string.diff_status_added) to palette.addedText
        "deleted" -> stringResource(R.string.diff_status_deleted) to palette.removedText
        else -> stringResource(R.string.diff_status_modified) to palette.hunkText
    }
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun HunkView(hunk: DiffHunk) {
    val palette = diffPalette()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = hunk.header,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = palette.hunkText,
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.hunkBackground)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Column {
                hunk.lines.forEach { line ->
                    val background = when (line.kind) {
                        DiffLineKind.ADDED -> palette.addedBackground
                        DiffLineKind.REMOVED -> palette.removedBackground
                        else -> MaterialTheme.colorScheme.surface
                    }
                    val textColor = when (line.kind) {
                        DiffLineKind.ADDED -> palette.addedText
                        DiffLineKind.REMOVED -> palette.removedText
                        DiffLineKind.NOTE -> palette.noteText
                        DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurface
                    }
                    val marker = when (line.kind) {
                        DiffLineKind.ADDED -> "+"
                        DiffLineKind.REMOVED -> "−"
                        DiffLineKind.NOTE -> "\\"
                        DiffLineKind.CONTEXT -> " "
                    }
                    Row(
                        modifier = Modifier
                            .background(background)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Gutter(line.oldLineNumber)
                        Gutter(line.newLineNumber)
                        Text(
                            text = marker,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.csp,
                            color = textColor,
                            modifier = Modifier.width(12.dp),
                        )
                        Text(
                            text = line.text.ifEmpty { " " },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.csp,
                            softWrap = false,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Gutter(number: Int?) {
    Text(
        text = number?.toString().orEmpty(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.csp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier
            .width(34.dp)
            .padding(end = 4.dp),
    )
}

@Composable
private fun RawPatch(patch: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = patch,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.csp,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("unused")
private fun diffSummary(file: DiffFile): String = "${file.path}: +${file.additions} −${file.deletions}"
