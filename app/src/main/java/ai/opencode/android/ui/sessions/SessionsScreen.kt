package ai.opencode.android.ui.sessions

import ai.opencode.android.data.model.ServerSession
import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalServerState
import ai.opencode.android.ui.components.AdaptiveContent
import ai.opencode.android.ui.components.ConnectionBadge
import ai.opencode.android.ui.components.EmptyState
import ai.opencode.android.ui.components.ErrorCard
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.format.ellipsize
import ai.opencode.android.ui.format.formatRelativeTime
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    container: AppContainer,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val viewModel: SessionsViewModel = viewModel(factory = SessionsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sessionToDelete by remember { mutableStateOf<ServerSession?>(null) }
    var setupPrompted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.localNeedsSetup) {
        if (state.localNeedsSetup && !setupPrompted) {
            setupPrompted = true
            onOpenSetup()
        }
    }

    LaunchedEffect(state.pendingOpenSessionId) {
        val id = state.pendingOpenSessionId
        if (id != null) {
            onOpenSession(id)
            viewModel.consumeOpenSession()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("opencode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = state.serverLabel.ifBlank { "no server configured" }.ellipsize(48),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    ConnectionBadge(state = state.connection, modifier = Modifier.padding(end = 4.dp))
                    if (state.connecting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh sessions")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.createSession() },
                icon = {
                    if (state.creating) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                },
                text = { Text("New session") },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            AdaptiveContent {
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
                if (state.fromCache && state.sessions.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Showing cached sessions — server is unreachable",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.connect() }) { Text("Connect") }
                        }
                    }
                }

                when {
                    state.loading && state.sessions.isEmpty() -> LoadingIndicator("Loading sessions…")
                    state.sessions.isEmpty() -> Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyState(
                            title = if (state.connected) "No sessions yet" else "Not connected",
                            message = if (state.connected) {
                                "Create a session to start a conversation with the agent."
                            } else {
                                "Point the app at an opencode server (Settings → Server), then connect."
                            },
                            actionLabel = if (state.connected) "New session" else "Open settings",
                            onAction = { if (state.connected) viewModel.createSession() else onOpenSettings() },
                        )
                    }

                    else -> LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 4.dp,
                            bottom = 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.sessions, key = { it.id }) { session ->
                            SessionCard(
                                session = session,
                                onClick = { onOpenSession(session.id) },
                                onDelete = { sessionToDelete = session },
                            )
                        }
                    }
                }
            }
        }
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete session?") },
            text = { Text("This removes \"${session.title}\" and its messages from the server. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(session.id)
                    sessionToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LocalServerBanner(
    state: SessionsUiState,
    onOpenSetup: () -> Unit,
    onStart: () -> Unit,
) {
    val server = state.localServer
    val (title, actionLabel, action) = when {
        state.localNeedsSetup -> Triple("Finish setup to run opencode on this phone", "Open setup", onOpenSetup)
        server.phase == LocalServerState.Phase.FAILED ->
            Triple("Local server failed: ${server.message ?: "unknown error"}", "Open setup", onOpenSetup)

        server.phase == LocalServerState.Phase.STARTING ->
            Triple("Starting the local server…", "Setup", onOpenSetup)

        server.phase == LocalServerState.Phase.STOPPED ->
            Triple("The local server is not running", "Start", onStart)

        else -> Triple("", "", onOpenSetup)
    }
    if (title.isBlank()) return

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = action) { Text(actionLabel) }
        }
    }
}

@Composable
private fun SessionCard(session: ServerSession, onClick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.busy) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = session.title.ifBlank { "Untitled session" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    session.directory?.takeIf { it.isNotBlank() }?.let { directory ->
                        Text(
                            text = directory.ellipsize(42),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = formatRelativeTime(session.updated),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Session actions")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete session") },
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
