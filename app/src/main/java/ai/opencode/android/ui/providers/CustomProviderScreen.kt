package ai.opencode.android.ui.providers

import ai.opencode.android.R
import ai.opencode.android.data.model.CustomProviderDraft
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.AdaptiveContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Adds an OpenAI-compatible endpoint (LM Studio, Ollama, vLLM, a proxy) to the server config. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomProviderScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: CustomProviderViewModel = viewModel(factory = CustomProviderViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var name by rememberSaveable { mutableStateOf("") }
    var id by rememberSaveable { mutableStateOf("") }
    var idEdited by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var models by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }

    val manualModels = models.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    val pickedModels = state.remoteModels.map { it.id }.filter { it in state.selected }
    val modelList = (pickedModels + manualModels).distinct()
    val idOk = CustomProviderViewModel.ID_PATTERN.matches(id)
    val urlOk = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    val modelsOk = modelList.isNotEmpty()

    val doneMessage = state.message?.resolve()
    LaunchedEffect(state.done) {
        if (state.done) {
            doneMessage?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.providers_custom_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        AdaptiveContent(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.providers_custom_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (!idEdited) id = CustomProviderViewModel.slug(it)
                    },
                    label = { Text(stringResource(R.string.providers_custom_name)) },
                    placeholder = { Text(stringResource(R.string.providers_custom_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = id,
                    onValueChange = {
                        id = it.lowercase().trim()
                        idEdited = true
                    },
                    label = { Text(stringResource(R.string.providers_custom_id)) },
                    supportingText = {
                        Text(stringResource(if (showErrors && !idOk) R.string.providers_custom_err_id else R.string.providers_custom_id_hint))
                    },
                    isError = showErrors && !idOk,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(stringResource(R.string.providers_custom_url)) },
                    placeholder = { Text("http://192.168.1.20:1234/v1") },
                    supportingText = if (showErrors && !urlOk) {
                        { Text(stringResource(R.string.providers_custom_err_url)) }
                    } else {
                        null
                    },
                    isError = showErrors && !urlOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim() },
                    label = { Text(stringResource(R.string.providers_custom_key)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                RemoteModelsSection(
                    state = state,
                    canFetch = urlOk,
                    onFetch = { viewModel.fetchModels(url.trim(), key) },
                    onToggle = viewModel::toggle,
                    onSelect = viewModel::select,
                    onSelectWorking = viewModel::selectWorking,
                    onTest = { ids -> viewModel.test(url.trim(), key, ids) },
                    onStop = viewModel::stopTests,
                )
                if (manualModels.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { viewModel.test(url.trim(), key, manualModels) },
                        enabled = urlOk && state.testing.isEmpty(),
                    ) { Text(stringResource(R.string.providers_probe_test_manual, manualModels.size)) }
                    manualModels.forEach { model -> state.tests[model]?.let { TestLine(model, it) } }
                }
                OutlinedTextField(
                    value = models,
                    onValueChange = { models = it },
                    label = { Text(stringResource(if (state.remoteModels.isEmpty()) R.string.providers_custom_models else R.string.providers_probe_manual)) },
                    supportingText = {
                        Text(stringResource(if (showErrors && !modelsOk) R.string.providers_custom_err_models else R.string.providers_custom_models_hint))
                    },
                    isError = showErrors && !modelsOk,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Text(it.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = {
                        showErrors = true
                        if (idOk && urlOk && modelsOk) {
                            viewModel.save(CustomProviderDraft(id, name.trim(), url.trim(), key, modelList))
                        }
                    },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(stringResource(R.string.providers_custom_save))
                }
            }
        }
    }
}
