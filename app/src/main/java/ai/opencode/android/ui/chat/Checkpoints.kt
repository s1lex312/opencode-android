package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.data.diff.UnifiedDiffParser
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.FileDiffPayload
import ai.opencode.android.data.model.Role
import ai.opencode.android.ui.diff.HunkView
import ai.opencode.android.ui.format.oneLine
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** `/rewind`: every prompt of the chat is a checkpoint; picking one previews what goes back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckpointsSheet(messages: List<ChatMessage>, onPick: (ChatMessage) -> Unit, onDismiss: () -> Unit) {
    val prompts = messages.filter { it.role == Role.USER && !it.id.startsWith("local-") }.asReversed()
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Text(
                    stringResource(R.string.checkpoints_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.checkpoints_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (prompts.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.checkpoints_empty),
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(prompts, key = { it.id }) { message ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(message) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            message.plainText.oneLine(140).ifBlank { stringResource(R.string.chat_untitled) },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (message.created > 0) {
                            Text(
                                timeFormat.format(Date(message.created)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What a revert would undo, file by file with the diffs, before anything changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevertPreviewSheet(preview: RevertPreview, onConfirm: () -> Unit, onFork: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(stringResource(R.string.revert_preview_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        preview.target.plainText.oneLine(160).ifBlank { stringResource(R.string.chat_untitled) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.size(8.dp))
                    val summary = when {
                        preview.loading -> stringResource(R.string.revert_preview_loading)
                        preview.unavailable && preview.turns.isEmpty() -> stringResource(R.string.revert_preview_unavailable)
                        preview.turns.isEmpty() -> stringResource(R.string.revert_preview_no_files)
                        else -> LocalContext.current.resources.getQuantityString(R.plurals.revert_preview_files, preview.fileCount, preview.fileCount)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (preview.loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.size(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onConfirm, enabled = !preview.loading) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.revert_preview_confirm))
                        }
                        OutlinedButton(onClick = onFork) {
                            Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.chat_action_fork))
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    HorizontalDivider()
                }
            }
            preview.turns.forEach { turn ->
                item(key = "turn-" + turn.message.id) {
                    Text(
                        text = "› " + turn.message.plainText.oneLine(90),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                items(turn.files, key = { "file-" + turn.message.id + it.file }) { file ->
                    CheckpointFile(file)
                }
            }
        }
    }
}

@Composable
private fun CheckpointFile(file: FileDiffPayload) {
    var open by remember(file.file) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = file.patch.isNotBlank()) { open = !open }.padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = file.file,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "+${file.additions ?: 0} −${file.deletions ?: 0}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (file.patch.isNotBlank()) {
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (open) {
            UnifiedDiffParser.parse(file.patch).flatMap { it.hunks }.forEach { hunk -> HunkView(hunk) }
        }
    }
}
