package ai.opencode.android.ui.git

import ai.opencode.android.R
import ai.opencode.android.data.git.GitFile
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.AdaptiveContent
import ai.opencode.android.ui.components.EmptyState
import ai.opencode.android.ui.components.ErrorCard
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.diff.HunkView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** What a destructive confirmation is about to do. */
private sealed interface PendingDiscard {
    data class File(val file: GitFile) : PendingDiscard
    data class Hunk(val file: GitFile, val index: Int) : PendingDiscard
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(container: AppContainer, cwd: String?, onBack: () -> Unit) {
    val viewModel: GitViewModel = viewModel(key = "git-$cwd", factory = GitViewModel.factory(container, cwd))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf<PendingDiscard?>(null) }
    val status = state.status
    val idle = state.running == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.git_title), style = MaterialTheme.typography.titleMedium)
                        val branch = status?.branch
                        if (branch != null) {
                            val tracking = buildString {
                                append(branch)
                                status.upstream?.let { append(" → ").append(it) }
                                if (status.ahead > 0) append("  ↑").append(status.ahead)
                                if (status.behind > 0) append("  ↓").append(status.behind)
                            }
                            Text(
                                tracking,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::pull, enabled = idle && status?.isRepo == true) {
                        Icon(Icons.Default.CloudDownload, contentDescription = stringResource(R.string.git_pull))
                    }
                    IconButton(onClick = viewModel::push, enabled = idle && status?.isRepo == true) {
                        Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.git_push))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_refresh)) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    viewModel.refresh()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_fetch)) },
                                leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                                enabled = idle,
                                onClick = {
                                    menu = false
                                    viewModel.fetch()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        AdaptiveContent(Modifier.padding(padding), maxWidth = 1000.dp) {
            Column(Modifier.fillMaxSize()) {
                if (state.loading || state.running != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { ErrorCard(message = it, onRetry = viewModel::refresh) }
                when {
                    status == null && state.loading -> LoadingIndicator(stringResource(R.string.git_loading))
                    status == null -> Unit
                    !status.isRepo -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            title = stringResource(R.string.git_not_repo_title),
                            message = stringResource(R.string.git_not_repo_body, state.directory ?: "~"),
                        )
                        Button(onClick = viewModel::init, enabled = idle) { Text(stringResource(R.string.git_init)) }
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                        state.output?.let { output ->
                            item(key = "output") { OutputCard(output, onDismiss = viewModel::dismissOutput) }
                        }
                        item(key = "commit") {
                            CommitBox(
                                message = state.message,
                                amend = state.amend,
                                enabled = idle,
                                nothingStaged = status.staged.isEmpty(),
                                hasChanges = status.files.isNotEmpty(),
                                onMessage = viewModel::setMessage,
                                onAmend = viewModel::setAmend,
                                onCommit = { viewModel.commit(push = false) },
                                onCommitPush = { viewModel.commit(push = true) },
                            )
                        }
                        if (status.files.isEmpty()) {
                            item(key = "clean") {
                                Text(
                                    stringResource(R.string.git_clean),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(20.dp),
                                )
                            }
                        }
                        if (status.staged.isNotEmpty()) {
                            item(key = "staged-header") {
                                SectionTitle(stringResource(R.string.git_staged, status.staged.size), stringResource(R.string.git_unstage_all), idle, viewModel::unstageAll)
                            }
                            items(status.staged, key = { "S" + it.path }) { file ->
                                FileRow(
                                    file = file,
                                    staged = true,
                                    diff = state.diffs[GitViewModel.key(file, true)],
                                    enabled = idle,
                                    onToggle = { viewModel.toggle(file, true) },
                                    onPrimary = { viewModel.unstage(file) },
                                    onDiscard = null,
                                    onHunk = { index -> viewModel.hunk(file, staged = true, index = index) },
                                    onDiscardHunk = null,
                                )
                            }
                        }
                        if (status.unstaged.isNotEmpty()) {
                            item(key = "worktree-header") {
                                SectionTitle(stringResource(R.string.git_changes, status.unstaged.size), stringResource(R.string.git_stage_all), idle, viewModel::stageAll)
                            }
                            items(status.unstaged, key = { "W" + it.path }) { file ->
                                FileRow(
                                    file = file,
                                    staged = false,
                                    diff = state.diffs[GitViewModel.key(file, false)],
                                    enabled = idle,
                                    onToggle = { viewModel.toggle(file, false) },
                                    onPrimary = { viewModel.stage(file) },
                                    onDiscard = { discard = PendingDiscard.File(file) },
                                    onHunk = { index -> viewModel.hunk(file, staged = false, index = index) },
                                    onDiscardHunk = { index -> discard = PendingDiscard.Hunk(file, index) },
                                )
                            }
                        }
                        if (state.log.isNotEmpty()) {
                            item(key = "log-header") {
                                Text(
                                    stringResource(R.string.git_log),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
                                )
                            }
                            items(state.log, key = { "C" + it.hash }) { commit ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                                    Text(
                                        commit.hash,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.width(72.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(commit.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${commit.author} · ${commit.age}",
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
        }
    }

    discard?.let { pending ->
        AlertDialog(
            onDismissRequest = { discard = null },
            title = { Text(stringResource(R.string.git_discard_title)) },
            text = {
                Text(
                    when (pending) {
                        is PendingDiscard.File -> stringResource(R.string.git_discard_file, pending.file.path)
                        is PendingDiscard.Hunk -> stringResource(R.string.git_discard_hunk, pending.file.path)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (pending) {
                        is PendingDiscard.File -> viewModel.discard(pending.file)
                        is PendingDiscard.Hunk -> viewModel.hunk(pending.file, staged = false, index = pending.index, discard = true)
                    }
                    discard = null
                }) { Text(stringResource(R.string.git_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { discard = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(title: String, action: String, enabled: Boolean, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun CommitBox(
    message: String,
    amend: Boolean,
    enabled: Boolean,
    nothingStaged: Boolean,
    hasChanges: Boolean,
    onMessage: (String) -> Unit,
    onAmend: (Boolean) -> Unit,
    onCommit: () -> Unit,
    onCommitPush: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = message,
                onValueChange = onMessage,
                label = { Text(stringResource(R.string.git_message)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = amend, onCheckedChange = onAmend)
                Text(stringResource(R.string.git_amend), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (nothingStaged && hasChanges) {
                Text(
                    stringResource(R.string.git_commit_all_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val canCommit = enabled && (message.isNotBlank() || amend) && (hasChanges || amend)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCommit, enabled = canCommit) { Text(stringResource(R.string.git_commit)) }
                OutlinedButton(onClick = onCommitPush, enabled = canCommit) { Text(stringResource(R.string.git_commit_push)) }
            }
        }
    }
}

@Composable
private fun OutputCard(output: GitOutput, onDismiss: () -> Unit) {
    Surface(
        color = if (output.ok) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$ " + output.title,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close)) }
            }
            Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(end = 12.dp)) {
                Text(output.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FileRow(
    file: GitFile,
    staged: Boolean,
    diff: FileDiffState?,
    enabled: Boolean,
    onToggle: () -> Unit,
    onPrimary: () -> Unit,
    onDiscard: (() -> Unit)?,
    onHunk: (Int) -> Unit,
    onDiscardHunk: ((Int) -> Unit)?,
) {
    val code = if (staged) file.index else if (file.untracked) '?' else file.worktree
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (code == '?') "U" else code.toString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = statusColor(code),
                modifier = Modifier.width(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val dir = file.path.substringBeforeLast('/', "")
                if (dir.isNotEmpty() || file.origPath != null) {
                    Text(
                        file.origPath?.let { "$it → $dir" } ?: dir,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (diff != null) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            if (onDiscard != null) {
                IconButton(onClick = onDiscard, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.git_discard))
                }
            }
            IconButton(onClick = onPrimary, enabled = enabled) {
                Icon(
                    if (staged) Icons.Default.Remove else Icons.Default.Add,
                    contentDescription = stringResource(if (staged) R.string.git_unstage else R.string.git_stage),
                )
            }
        }
        if (diff != null) {
            when {
                diff.loading -> LoadingIndicator()
                diff.raw == null -> Text(
                    diff.error ?: stringResource(R.string.git_no_diff),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                diff.raw.binary -> Text(
                    stringResource(R.string.git_binary),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                else -> diff.hunks.forEachIndexed { index, hunk ->
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))) {
                        HunkView(hunk)
                        if (file.hunksOn(staged)) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.End) {
                                if (onDiscardHunk != null) {
                                    TextButton(onClick = { onDiscardHunk(index) }, enabled = enabled) {
                                        Text(stringResource(R.string.git_discard_hunk_action), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                TextButton(onClick = { onHunk(index) }, enabled = enabled) {
                                    Text(stringResource(if (staged) R.string.git_unstage_hunk else R.string.git_stage_hunk))
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun statusColor(code: Char): Color = when (code) {
    'A', '?' -> Color(0xFF3FA34D)
    'D' -> MaterialTheme.colorScheme.error
    'U' -> MaterialTheme.colorScheme.error
    'R', 'C' -> MaterialTheme.colorScheme.tertiary
    else -> Color(0xFFD08A1E)
}
