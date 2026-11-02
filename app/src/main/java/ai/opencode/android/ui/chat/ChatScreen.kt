package ai.opencode.android.ui.chat

import ai.opencode.android.R
import ai.opencode.android.data.model.ChatMessage
import ai.opencode.android.data.model.Role
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.ErrorCard
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.format.oneLine
import ai.opencode.android.ui.models.ModelPickerSheet
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
    onOpenDiff: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenTerminal: (String?) -> Unit,
    onOpenSystemPrompts: () -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(
        key = "chat-$sessionId",
        factory = ChatViewModel.factory(container, sessionId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by remember(sessionId) { mutableStateOf(TextFieldValue("")) }
    var modelSheet by rememberSaveable { mutableStateOf(false) }
    var usageSheet by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // reverseLayout: index 0 is the newest item, so "away from the bottom" means a later index.
    val awayFromLatest by remember { derivedStateOf { listState.firstVisibleItemIndex > 1 } }
    val shareChooserTitle = stringResource(R.string.chat_share_chooser)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ChatEffect.OpenSession -> onOpenSession(effect.sessionId)
                is ChatEffect.RestoreInput -> input = TextFieldValue(effect.text, androidx.compose.ui.text.TextRange(effect.text.length))
                is ChatEffect.Toast -> Toast.makeText(context, effect.text.resolve(context), Toast.LENGTH_LONG).show()
                is ChatEffect.ShareLink -> {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, effect.url)
                    runCatching { context.startActivity(Intent.createChooser(send, shareChooserTitle)) }
                }
            }
        }
    }

    // `@` and `/` popups follow the caret.
    val token = composerToken(input)
    LaunchedEffect(token) {
        viewModel.searchFiles((token as? ComposerToken.Mention)?.query)
    }
    val commandSuggestions = (token as? ComposerToken.Command)?.let { viewModel.commandSuggestions(it.prefix) }.orEmpty()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        uris.forEach { uri -> scope.launch { addAttachment(context, viewModel, uri) } }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> scope.launch { addAttachment(context, viewModel, uri) } }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            val joined = listOf(input.text.trimEnd(), spoken).filter { it.isNotBlank() }.joinToString(" ")
            input = TextFieldValue(joined, androidx.compose.ui.text.TextRange(joined.length))
        }
    }
    val voiceUnavailable = stringResource(R.string.chat_voice_unavailable)
    val voicePrompt = stringResource(R.string.chat_voice_prompt)

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
                                state.compacting -> stringResource(R.string.usage_compacting)
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
                            MenuItem(Icons.Default.Share, stringResource(if (state.shareUrl != null) R.string.chat_share_again else R.string.chat_share)) {
                                menuOpen = false
                                viewModel.share()
                            }
                            if (state.shareUrl != null) {
                                MenuItem(Icons.Default.LinkOff, stringResource(R.string.chat_unshare)) {
                                    menuOpen = false
                                    viewModel.unshare()
                                }
                            }
                            MenuItem(Icons.AutoMirrored.Filled.CallSplit, stringResource(R.string.chat_fork)) {
                                menuOpen = false
                                viewModel.forkFrom(null)
                            }
                            MenuItem(Icons.Default.Compress, stringResource(R.string.usage_compact)) {
                                menuOpen = false
                                viewModel.compact()
                            }
                            MenuItem(Icons.Default.Terminal, stringResource(R.string.terminal_title)) {
                                menuOpen = false
                                onOpenTerminal(state.directory)
                            }
                            HorizontalDivider()
                            MenuItem(Icons.Default.Settings, stringResource(R.string.action_settings)) {
                                menuOpen = false
                                onOpenSettings()
                            }
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
                        text = context.resources.getQuantityString(R.plurals.chat_permission_queue, pending.size - 1, pending.size - 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, bottom = 4.dp),
                    )
                }
                Composer(
                    value = input,
                    onValueChange = { input = it },
                    state = state,
                    commandSuggestions = commandSuggestions,
                    onSend = {
                        val text = input.text
                        if ((text.isNotBlank() || state.attachments.isNotEmpty()) && state.connected) {
                            input = TextFieldValue("")
                            viewModel.send(text)
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    },
                    onStop = viewModel::abort,
                    onPickModel = {
                        modelSheet = true
                        if (state.models.isEmpty()) viewModel.loadModels()
                    },
                    onSelectAgent = viewModel::selectAgent,
                    onPickSystemPrompt = viewModel::selectSystemPrompt,
                    onManageSystemPrompts = onOpenSystemPrompts,
                    onOpenUsage = { usageSheet = true },
                    onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onPickFile = { filePicker.launch(arrayOf("*/*")) },
                    onVoice = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt)
                        try {
                            voice.launch(intent)
                        } catch (missing: ActivityNotFoundException) {
                            Toast.makeText(context, voiceUnavailable, Toast.LENGTH_LONG).show()
                        }
                    },
                    onRemoveAttachment = viewModel::removeAttachment,
                    onPickMention = { path ->
                        val mention = token as? ComposerToken.Mention ?: return@Composer
                        input = input.withMention(mention, path)
                        viewModel.pickMention(path)
                    },
                    onPickCommand = { name -> input = commandValue(name) },
                    onRemoveQueued = viewModel::removeQueued,
                    onEditQueued = viewModel::editQueued,
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
            if (state.revertMessageId != null && state.revertedCount > 0) {
                RevertBanner(count = state.revertedCount, onRestore = viewModel::unrevert)
            }

            val visible = state.visibleMessages
            when {
                state.loading && visible.isEmpty() -> LoadingIndicator()
                visible.isEmpty() -> EmptyConversation(
                    modifier = Modifier.weight(1f),
                    onSuggestion = { input = TextFieldValue(it, androidx.compose.ui.text.TextRange(it.length)) },
                )

                else -> {
                    val reversed = visible.asReversed()
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
                            MessageItem(message = message, onLongPress = { selectedMessage = message })
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

    if (usageSheet) {
        UsageSheet(
            usage = state.usage,
            compacting = state.compacting,
            onCompact = {
                usageSheet = false
                viewModel.compact()
            },
            onDismiss = { usageSheet = false },
        )
    }

    selectedMessage?.let { message ->
        MessageActionsSheet(
            message = message,
            onCopy = {
                copyToClipboard(context, message.plainText)
                selectedMessage = null
            },
            onRevert = {
                selectedMessage = null
                viewModel.revertTo(message)
            },
            onFork = {
                selectedMessage = null
                viewModel.forkFrom(message)
            },
            onDismiss = { selectedMessage = null },
        )
    }
}

private const val MAX_PICK = 6

private suspend fun addAttachment(context: Context, viewModel: ChatViewModel, uri: android.net.Uri) {
    when (val result = Attachments.read(context, uri)) {
        is Attachments.Result.Ok -> viewModel.addAttachment(result.attachment)
        is Attachments.Result.Err -> viewModel.attachmentError(result.message)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("message", text))
    Toast.makeText(context, context.getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun RevertBanner(count: Int, onRestore: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = LocalContext.current.resources.getQuantityString(R.plurals.chat_reverted, count, count),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestore) { Text(stringResource(R.string.chat_revert_restore)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: ChatMessage,
    onCopy: () -> Unit,
    onRevert: () -> Unit,
    onFork: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                text = message.plainText.oneLine(120).ifBlank { stringResource(R.string.chat_untitled) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SheetAction(Icons.Default.ContentCopy, stringResource(R.string.chat_action_copy), null, onCopy)
            SheetAction(
                Icons.Default.Restore,
                stringResource(if (message.role == Role.USER) R.string.chat_action_revert_user else R.string.chat_action_revert),
                stringResource(R.string.chat_action_revert_hint),
                onRevert,
            )
            SheetAction(Icons.AutoMirrored.Filled.CallSplit, stringResource(R.string.chat_action_fork), stringResource(R.string.chat_action_fork_hint), onFork)
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.chat_empty_tips),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
