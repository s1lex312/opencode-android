package ai.opencode.android.ui.providers

import ai.opencode.android.R
import ai.opencode.android.data.model.FeaturedProviders
import ai.opencode.android.data.model.OAuthSession
import ai.opencode.android.data.model.ProviderAuthMethod
import ai.opencode.android.data.model.ProviderInfo
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.AdaptiveContent
import ai.opencode.android.ui.components.EmptyState
import ai.opencode.android.ui.components.ErrorCard
import ai.opencode.android.ui.components.LoadingIndicator
import ai.opencode.android.ui.components.SectionHeader
import ai.opencode.android.ui.theme.StatusColors
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Rocket
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAddCustomProvider: () -> Unit,
) {
    val viewModel: ProvidersViewModel = viewModel(factory = ProvidersViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val openFailed = stringResource(R.string.providers_open_failed)

    val message = state.message?.resolve()
    LaunchedEffect(state.message) {
        if (message != null) {
            viewModel.messageShown()
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.providers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onAddCustomProvider) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.providers_add_custom))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        AdaptiveContent(Modifier.padding(padding)) {
            when {
                !state.connected -> EmptyState(
                    title = stringResource(R.string.providers_not_connected_title),
                    message = stringResource(R.string.providers_not_connected_body),
                    icon = Icons.Default.CloudOff,
                )

                state.loading && state.providers.isEmpty() -> LoadingIndicator()

                state.error != null && state.providers.isEmpty() -> ErrorCard(
                    message = state.error!!.resolve(),
                    onRetry = viewModel::load,
                )

                state.providers.isEmpty() -> EmptyState(
                    title = stringResource(R.string.providers_empty_title),
                    message = stringResource(R.string.providers_empty_body),
                    actionLabel = stringResource(R.string.action_reload),
                    onAction = viewModel::load,
                )

                else -> ProviderList(
                    state = state,
                    onQuery = viewModel::setQuery,
                    onOpen = viewModel::open,
                    onGetKey = { context.openUrl(FeaturedProviders.CONSOLE_URL, openFailed) },
                )
            }
        }
    }

    state.selected?.let { provider ->
        ConnectSheet(
            provider = provider,
            state = state,
            onDismiss = viewModel::closeSheet,
            onApiKey = viewModel::connectWithKey,
            onOAuth = viewModel::startOAuth,
            onOAuthCode = { code -> viewModel.finishOAuth(code) },
            onDisconnect = viewModel::disconnect,
            onOpenUrl = { url -> context.openUrl(url, openFailed) },
        )
    }
}

@Composable
private fun ProviderList(
    state: ProvidersUiState,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onGetKey: () -> Unit,
) {
    val searching = state.query.isNotBlank()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item(key = "search") {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.providers_search_hint)) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (!searching) {
            val go = state.goProvider
            if (go != null && !go.connected) {
                item(key = "go-hero") { GoHero(onConnect = { onOpen(go.id) }, onGetKey = onGetKey) }
            }
            if (state.connectedProviders.isNotEmpty()) {
                item(key = "h-connected") { SectionHeader(stringResource(R.string.providers_section_connected)) }
                items(state.connectedProviders, key = { "c-${it.id}" }) { ProviderRow(it) { onOpen(it.id) } }
            }
            val recommended = state.recommended.filter { it.id != FeaturedProviders.OPENCODE_GO }
            if (recommended.isNotEmpty()) {
                item(key = "h-recommended") { SectionHeader(stringResource(R.string.providers_section_recommended)) }
                items(recommended, key = { "r-${it.id}" }) { ProviderRow(it) { onOpen(it.id) } }
            }
            item(key = "h-all") { SectionHeader(stringResource(R.string.providers_section_all)) }
        }
        val results = state.search()
        if (results.isEmpty()) {
            item(key = "no-match") {
                Text(
                    text = stringResource(R.string.providers_no_match, state.query.trim()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(results, key = { "a-${it.id}" }) { ProviderRow(it) { onOpen(it.id) } }
    }
}

@Composable
private fun GoHero(onConnect: () -> Unit, onGetKey: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Rocket, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.providers_go_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = stringResource(R.string.providers_go_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) { Text(stringResource(R.string.providers_go_connect)) }
                OutlinedButton(onClick = onGetKey) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.providers_go_get_key))
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(provider: ProviderInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = if (provider.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = provider.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (provider.connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(provider.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = buildList {
                if (provider.connected) add(statusLabel(provider))
                if (provider.id == FeaturedProviders.OPENCODE_ZEN && !provider.connected) add(stringResource(R.string.providers_zen_hint))
                if (provider.modelCount > 0) {
                    add(LocalContext.current.resources.getQuantityString(R.plurals.providers_models_count, provider.modelCount, provider.modelCount))
                }
            }.joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (provider.connected) StatusColors.ok else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun statusLabel(provider: ProviderInfo): String = stringResource(
    when (provider.source) {
        "env" -> R.string.providers_status_env
        "config", "custom" -> R.string.providers_status_config
        else -> R.string.providers_status_connected
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectSheet(
    provider: ProviderInfo,
    state: ProvidersUiState,
    onDismiss: () -> Unit,
    onApiKey: (String) -> Unit,
    onOAuth: (ProviderAuthMethod.OAuth, Map<String, String>) -> Unit,
    onOAuthCode: (String) -> Unit,
    onDisconnect: (ProviderInfo) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var methodIndex by rememberSaveable(provider.id) { mutableStateOf(0) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val method = provider.authMethods.getOrElse(methodIndex) { provider.authMethods.first() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(provider.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (provider.connected) {
                Text(statusLabel(provider), style = MaterialTheme.typography.bodyMedium, color = StatusColors.ok)
            }

            if (provider.authMethods.size > 1 && state.oauth == null) {
                Text(stringResource(R.string.providers_sheet_choose_method), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    provider.authMethods.forEachIndexed { index, option ->
                        androidx.compose.material3.FilterChip(
                            selected = index == methodIndex,
                            onClick = { methodIndex = index },
                            label = { Text(methodLabel(option), maxLines = 1) },
                        )
                    }
                }
            }

            val oauth = state.oauth
            when {
                oauth != null -> OAuthProgress(oauth, state.busy, onOAuthCode, onOpenUrl)
                method is ProviderAuthMethod.ApiKey -> ApiKeyForm(provider, state.busy, onApiKey, onOpenUrl)
                method is ProviderAuthMethod.OAuth -> OAuthStart(method, state.busy, onOAuth)
            }

            state.sheetError?.let {
                Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (provider.connected) {
                if (provider.managedByEnvironment) {
                    Text(
                        text = stringResource(R.string.providers_env_cannot_disconnect, provider.env.joinToString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = { confirmDisconnect = true }, enabled = !state.busy) {
                        Text(stringResource(R.string.providers_disconnect), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.providers_disconnect_confirm_title, provider.name)) },
            text = { Text(stringResource(R.string.providers_disconnect_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    onDisconnect(provider)
                }) { Text(stringResource(R.string.providers_disconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun methodLabel(method: ProviderAuthMethod): String = method.label?.takeIf { it.isNotBlank() }
    ?: stringResource(
        if (method is ProviderAuthMethod.OAuth) R.string.providers_method_browser else R.string.providers_method_api_key,
    )

@Composable
private fun ApiKeyForm(
    provider: ProviderInfo,
    busy: Boolean,
    onConnect: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val context = LocalContext.current
    var key by rememberSaveable(provider.id) { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = key,
        onValueChange = { key = it.trim() },
        label = { Text(stringResource(R.string.providers_key_label)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
        trailingIcon = {
            Row {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { key = it }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.providers_paste))
                }
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(if (visible) R.string.providers_hide_key else R.string.providers_show_key),
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    val hint = buildString {
        append(stringResource(R.string.providers_key_hint))
        if (provider.env.isNotEmpty()) {
            append(' ')
            append(stringResource(R.string.providers_key_env_hint, provider.env.joinToString()))
        }
    }
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (provider.id == FeaturedProviders.OPENCODE_GO || provider.id == FeaturedProviders.OPENCODE_ZEN) {
        TextButton(onClick = { onOpenUrl(FeaturedProviders.CONSOLE_URL) }) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.providers_key_where))
        }
    }
    ProgressButton(
        label = stringResource(R.string.providers_connect),
        busy = busy,
        enabled = key.isNotBlank(),
        onClick = { onConnect(key) },
    )
}

@Composable
private fun OAuthStart(
    method: ProviderAuthMethod.OAuth,
    busy: Boolean,
    onStart: (ProviderAuthMethod.OAuth, Map<String, String>) -> Unit,
) {
    val answers = remember(method) { mutableStateMapOf<String, String>() }
    method.prompts.forEach { prompt ->
        if (prompt.options.isNotEmpty()) {
            Text(prompt.message, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prompt.options.forEach { option ->
                    androidx.compose.material3.FilterChip(
                        selected = answers[prompt.key] == option.value,
                        onClick = { answers[prompt.key] = option.value },
                        label = { Text(option.label) },
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = answers[prompt.key].orEmpty(),
                onValueChange = { answers[prompt.key] = it },
                label = { Text(prompt.message) },
                placeholder = prompt.placeholder?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    ProgressButton(
        label = stringResource(R.string.providers_method_browser),
        busy = busy,
        enabled = true,
        icon = { Icon(Icons.Default.Login, contentDescription = null) },
        onClick = { onStart(method, answers.toMap()) },
    )
}

@Composable
private fun OAuthProgress(
    session: OAuthSession,
    busy: Boolean,
    onCode: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    // Open the provider's page once; the button below reopens it if the user switched away.
    LaunchedEffect(session.url) { onOpenUrl(session.url) }
    session.instructions?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
    }
    OutlinedButton(onClick = { onOpenUrl(session.url) }) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.providers_oauth_open))
    }
    if (session.needsCode) {
        var code by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.providers_oauth_code_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ProgressButton(
            label = stringResource(R.string.providers_oauth_submit),
            busy = busy,
            enabled = code.isNotBlank(),
            onClick = { onCode(code) },
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.providers_oauth_waiting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.providers_connecting))
        } else {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Text(label)
        }
    }
}

/** Opens [url] in a browser; a toast instead of a crash when nothing can handle it. */
internal fun Context.openUrl(url: String, failure: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, failure, Toast.LENGTH_SHORT).show()
    }
}
