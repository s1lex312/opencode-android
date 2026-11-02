package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.ErrorCard
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.format.oneLine
import ai.opencode.android.ui.models.ModelPickerSheet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
    onOpenDiff: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(
        key = "chat-$sessionId",
        factory = ChatViewModel.factory(container, sessionId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var input by rememberSaveable(sessionId) { mutableStateOf("") }
    var modelSheet by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // reverseLayout: index 0 is the newest item, so "away from the bottom" means a later index.
    val awayFromLatest by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.title.ifBlank { stringResource(R.string.chat_untitled) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = when {
                                !state.connected -> stringResource(R.string.chat_status_offline)
                                state.busy -> state.retryMessage?.oneLine(50) ?: stringResource(R.string.chat_status_working)
                                else -> stringResource(R.string.chat_status_connected)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDiff) {
                        Icon(Icons.Default.Difference, contentDescription = stringResource(R.string.chat_diff))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenSettings()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                val pending = state.permissions
                pending.firstOrNull()?.let { permission ->
                    PermissionPrompt(
                        request = permission,
                        onDecision = { decision -> viewModel.replyPermission(permission, decision) },
                    )
                }
                if (pending.size > 1) {
                    Text(
                        text = LocalContext.current.resources.getQuantityString(
                            R.plurals.chat_permission_queue, pending.size - 1, pending.size - 1,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 4.dp),
                    )
                }
                Composer(
                    value = input,
                    onValueChange = { input = it },
                    state = state,
                    onSend = {
                        val text = input
                        if (text.isNotBlank() && state.canSend) {
                            input = ""
                            viewModel.send(text)
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    },
                    onStop = viewModel::abort,
                    onPickModel = {
                        modelSheet = true
                        if (state.models.isEmpty()) viewModel.loadModels()
                    },
                    onAgentsNeeded = { if (state.agents.isEmpty()) viewModel.loadAgents() },
                    onSelectAgent = viewModel::selectAgent,
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(visible = awayFromLatest, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.chat_scroll_bottom))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.connectionError?.let { error ->
                ErrorCard(message = error, onRetry = viewModel::retry)
            }
            state.error?.let { error ->
                ErrorCard(message = error, onRetry = viewModel::retry, onDismiss = viewModel::dismissError)
            }

            when {
                state.loading && state.messages.isEmpty() -> LoadingIndicator()
                state.messages.isEmpty() -> EmptyConversation(
                    modifier = Modifier.weight(1f),
                    onSuggestion = { input = it },
                )

                else -> {
                    val reversed = state.messages.asReversed()
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.weight(1f).fillMaxWidth().widthIn(max = 900.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        if (state.busy) {
                            item(key = "typing-indicator") { TypingIndicator() }
                        }
                        items(reversed, key = { message -> message.id }) { message ->
                            MessageItem(message = message)
                        }
                    }
                }
            }
        }
    }

    if (modelSheet) {
        ModelPickerSheet(
            models = state.models,
            selected = state.model,
            loading = state.modelsLoading,
            error = state.modelsError,
            onSelect = { option ->
                viewModel.selectModel(option)
                modelSheet = false
            },
            onReload = viewModel::loadModels,
            onManageProviders = {
                modelSheet = false
                onOpenProviders()
            },
            onDismiss = { modelSheet = false },
        )
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier, onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(R.string.chat_suggestion_explain),
        stringResource(R.string.chat_suggestion_bug),
        stringResource(R.string.chat_suggestion_tests),
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(16.dp))
        suggestions.forEach { suggestion ->
            SuggestionChip(
                onClick = { onSuggestion(suggestion) },
                label = { Text(suggestion) },
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 180),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 5.dp).size(8.dp).alpha(alpha),
            ) {}
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.chat_working),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    state: ChatUiState,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickModel: () -> Unit,
    onAgentsNeeded: () -> Unit,
    onSelectAgent: (String) -> Unit,
) {
    var agentMenu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        ) {
            if (!state.connected) {
                Text(
                    text = stringResource(R.string.chat_waiting_server),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = onPickModel,
                    label = {
                        Text(
                            text = state.model?.modelID ?: stringResource(R.string.chat_model_default),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    shape = CircleShape,
                )
                Box {
                    AssistChip(
                        onClick = {
                            onAgentsNeeded()
                            agentMenu = true
                        },
                        label = { Text(state.agent.ifBlank { stringResource(R.string.chat_agent_default) }, maxLines = 1) },
                        leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        trailingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        shape = CircleShape,
                    )
                    DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_agent_default)) },
                            onClick = {
                                agentMenu = false
                                onSelectAgent("")
                            },
                        )
                        state.agents.forEach { agent ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(agent.name)
                                        agent.description?.takeIf { it.isNotBlank() }?.let {
                                            Text(
                                                text = it.oneLine(60),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    agentMenu = false
                                    onSelectAgent(agent.name)
                                },
                            )
                        }
                    }
                }
            }
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)) {
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
                    val busy = state.busy
                    FilledIconButton(
                        onClick = if (busy) onStop else onSend,
                        enabled = busy || (state.connected && value.isNotBlank()),
                        colors = if (busy) {
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            IconButtonDefaults.filledIconButtonColors()
                        },
                    ) {
                        Icon(
                            imageVector = if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(if (busy) R.string.action_stop else R.string.chat_send),
                        )
                    }
                }
            }
        }
    }
}
