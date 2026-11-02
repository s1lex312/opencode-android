package ai.opencode.android.ui.sessions

import ai.opencode.android.R
import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.ui.components.AdaptiveContent
import ai.opencode.android.ui.components.ConnectionBadge
import ai.opencode.android.ui.components.EmptyState
import ai.opencode.android.ui.components.ErrorCard
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.format.ellipsize
import ai.opencode.android.ui.format.relativeTime
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    container: AppContainer,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    val viewModel: SessionsViewModel = viewModel(factory = SessionsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sessionToDelete by remember { mutableStateOf<ServerSession?>(null) }
    var setupPrompted by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    LaunchedEffect(state.localNeedsSetup) {
        if (state.localNeedsSetup && !setupPrompted) {
            setupPrompted = true
            onOpenSetup()
        }
    }

    // Coming back from the Providers screen: the model catalogue may have changed.
    LaunchedEffect(Unit) { if (state.connected) viewModel.checkProviders() }

    LaunchedEffect(state.pendingOpenSessionId) {
        val id = state.pendingOpenSessionId
        if (id != null) {
            onOpenSession(id)
            viewModel.consumeOpenSession()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.sessions_title))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (state.localMode) Icons.Default.PhoneAndroid else Icons.Default.Hub,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (state.localMode) {
                                    stringResource(R.string.sessions_on_this_phone)
                                } else {
                                    state.serverLabel.removePrefix("https://").removePrefix("http://").ellipsize(36)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    ConnectionBadge(state = state.connection, modifier = Modifier.padding(end = 4.dp))
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(
                            imageVector = if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = stringResource(R.string.action_search),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            if (state.connected) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.createSession() },
                    expanded = fabExpanded,
                    icon = {
                        if (state.creating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
                    },
                    text = { Text(stringResource(R.string.sessions_new_chat)) },
                )
            }
        },
    ) { padding ->
        AdaptiveContent(Modifier.padding(padding)) {
            AnimatedVisibility(visible = searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.sessions_search_hint)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (state.localMode) {
                LocalServerBanner(
                    state = state,
                    onOpenSetup = onOpenSetup,
                    onStart = {
                        container.startLocalServerService()
                        viewModel.connect()
                    },
                )
            }
            state.error?.let { error ->
                ErrorCard(
                    message = error,
                    hint = state.connectionHint,
                    onRetry = { if (!state.connected) viewModel.connect() else viewModel.refresh() },
                    onDismiss = viewModel::dismissError,
                )
            }
            if (state.connected && state.noProviders) {
                ProvidersNudge(onOpenProviders)
            }
            if (state.fromCache && state.sessions.isNotEmpty()) {
                Notice(
                    text = stringResource(R.string.sessions_cached),
                    icon = Icons.Default.CloudOff,
                    action = stringResource(R.string.action_connect),
                    onAction = { viewModel.connect() },
                )
            }

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                when {
                    state.loading && state.sessions.isEmpty() -> LoadingIndicator()
                    state.sessions.isEmpty() -> EmptySessions(state, onOpenSettings, onOpenSetup) { viewModel.createSession() }
                    else -> SessionList(
                        sessions = state.sessions,
                        query = query,
                        listState = listState,
                        onOpen = onOpenSession,
                        onDelete = { sessionToDelete = it },
                    )
                }
            }
        }
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text(stringResource(R.string.sessions_delete_title)) },
            text = {
                Text(stringResource(R.string.sessions_delete_body, session.title.ifBlank { stringResource(R.string.sessions_untitled) }))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(session.id)
                    sessionToDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private enum class DateGroup(val label: Int) {
    TODAY(R.string.sessions_group_today),
    YESTERDAY(R.string.sessions_group_yesterday),
    WEEK(R.string.sessions_group_week),
    OLDER(R.string.sessions_group_older),
}

private fun groupOf(updated: Long, startOfToday: Long): DateGroup {
    val day = 24L * 60 * 60 * 1000
    return when {
        updated >= startOfToday -> DateGroup.TODAY
        updated >= startOfToday - day -> DateGroup.YESTERDAY
        updated >= startOfToday - 7 * day -> DateGroup.WEEK
        else -> DateGroup.OLDER
    }
}

@Composable
private fun SessionList(
    sessions: List<ServerSession>,
    query: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpen: (String) -> Unit,
    onDelete: (ServerSession) -> Unit,
) {
    val needle = query.trim()
    val startOfToday = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val groups = remember(sessions, needle) {
        sessions
            .filter {
                needle.isBlank() || it.title.contains(needle, true) || it.directory.orEmpty().contains(needle, true)
            }
            .groupBy { groupOf(it.updated, startOfToday) }
            .toSortedMap()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (groups.isEmpty()) {
            item(key = "no-match") {
                Text(
                    text = stringResource(R.string.sessions_no_match, needle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        groups.forEach { (group, items) ->
            item(key = "group-${group.name}") {
                Text(
                    text = stringResource(group.label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
                )
            }
            items(items, key = { it.id }) { session ->
                SwipeableSession(session, onOpen = { onOpen(session.id) }, onDelete = { onDelete(session) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSession(session: ServerSession, onOpen: () -> Unit, onDelete: () -> Unit) {
    // The swipe only asks; deletion happens after the confirmation dialog, so the row snaps back.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        SessionCard(session, onOpen, onDelete)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(session: ServerSession, onClick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val title = session.title.ifBlank { stringResource(R.string.sessions_untitled) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (session.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        val initial = title.firstOrNull { it.isLetterOrDigit() }?.uppercase()
                        if (initial != null) {
                            Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        } else {
                            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (session.busy) stringResource(R.string.sessions_working) else relativeTime(session.updated),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (session.busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    session.directory?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { folder ->
                        Text(
                            text = "  ·  ${folder.ellipsize(28)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.sessions_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySessions(
    state: SessionsUiState,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
    onCreate: () -> Unit,
) {
    // Inside PullToRefreshBox: a scrollable container keeps pull-to-refresh working on empty lists.
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        item {
            if (state.connected) {
                EmptyState(
                    title = stringResource(R.string.sessions_empty_title),
                    message = stringResource(R.string.sessions_empty_body),
                    icon = Icons.Default.ChatBubbleOutline,
                    actionLabel = stringResource(R.string.sessions_new_chat),
                    onAction = onCreate,
                )
            } else {
                EmptyState(
                    title = stringResource(R.string.sessions_offline_title),
                    message = stringResource(
                        if (state.localMode) R.string.sessions_offline_body_local else R.string.sessions_offline_body_remote,
                    ),
                    icon = Icons.Default.CloudOff,
                    actionLabel = stringResource(if (state.localMode) R.string.sessions_open_setup else R.string.sessions_open_settings),
                    onAction = if (state.localMode) onOpenSetup else onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun ProvidersNudge(onOpenProviders: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.sessions_providers_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.sessions_providers_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            FilledTonalButton(onClick = onOpenProviders) { Text(stringResource(R.string.sessions_providers_action)) }
        }
    }
}

@Composable
private fun Notice(text: String, icon: ImageVector, action: String, onAction: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun LocalServerBanner(
    state: SessionsUiState,
    onOpenSetup: () -> Unit,
    onStart: () -> Unit,
) {
    val server = state.localServer
    val unknown = stringResource(R.string.sessions_local_failed_unknown)
    val (text, action, onAction) = when {
        state.localNeedsSetup ->
            Triple(stringResource(R.string.sessions_local_setup), stringResource(R.string.sessions_open_setup), onOpenSetup)

        server.phase == LocalServerState.Phase.FAILED -> Triple(
            stringResource(R.string.sessions_local_failed, server.message ?: unknown),
            stringResource(R.string.sessions_open_setup),
            onOpenSetup,
        )

        server.phase == LocalServerState.Phase.STARTING ->
            Triple(stringResource(R.string.sessions_local_starting), stringResource(R.string.sessions_open_setup), onOpenSetup)

        server.phase == LocalServerState.Phase.STOPPED ->
            Triple(stringResource(R.string.sessions_local_stopped), stringResource(R.string.action_start), onStart)

        else -> return
    }
    Notice(text = text, icon = Icons.Default.PhoneAndroid, action = action, onAction = onAction)
}
