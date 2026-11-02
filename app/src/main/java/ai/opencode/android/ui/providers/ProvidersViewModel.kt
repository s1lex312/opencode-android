package ai.opencode.android.ui.providers

import ai.opencode.android.R
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.data.model.CustomProviderDraft
import ai.opencode.android.data.model.FeaturedProviders
import ai.opencode.android.data.model.OAuthSession
import ai.opencode.android.data.model.ProviderAuthMethod
import ai.opencode.android.data.model.ProviderInfo
import ai.opencode.android.data.repo.ApiResult
import ai.opencode.android.data.repo.ProviderRepository
import ai.opencode.android.data.repo.ServerConnectionManager
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.text.UiText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProvidersUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val providers: List<ProviderInfo> = emptyList(),
    val error: UiText? = null,
    val query: String = "",
    /** Provider whose connect sheet is open. */
    val selectedId: String? = null,
    val busy: Boolean = false,
    val sheetError: UiText? = null,
    /** A browser sign-in in progress for [selectedId]. */
    val oauth: OAuthSession? = null,
    val message: UiText? = null,
) {
    val selected: ProviderInfo? get() = providers.firstOrNull { it.id == selectedId }

    val connectedProviders: List<ProviderInfo> get() = providers.filter { it.connected }

    /** Featured providers that are not connected yet, in [FeaturedProviders.popular] order. */
    val recommended: List<ProviderInfo>
        get() = FeaturedProviders.popular.mapNotNull { id -> providers.firstOrNull { it.id == id && !it.connected } }

    val goProvider: ProviderInfo? get() = providers.firstOrNull { it.id == FeaturedProviders.OPENCODE_GO }

    fun search(): List<ProviderInfo> {
        val needle = query.trim()
        return providers
            .filter { needle.isBlank() || it.name.contains(needle, true) || it.id.contains(needle, true) }
            .sortedBy { it.name.lowercase() }
    }
}

class ProvidersViewModel(
    private val repository: ProviderRepository,
    private val connection: ServerConnectionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ProvidersUiState())
    val state: StateFlow<ProvidersUiState> = _state.asStateFlow()

    private var oauthJob: Job? = null

    init {
        viewModelScope.launch {
            connection.state
                .map { it is ConnectionState.Connected }
                .distinctUntilChanged()
                .collect { connected ->
                    _state.update { it.copy(connected = connected) }
                    if (connected) load()
                }
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.providers()) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, providers = result.value) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = UiText.raw(result.message)) }
            }
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun open(providerId: String) = _state.update {
        it.copy(selectedId = providerId, sheetError = null, oauth = null, busy = false)
    }

    fun closeSheet() {
        oauthJob?.cancel()
        _state.update { it.copy(selectedId = null, sheetError = null, oauth = null, busy = false) }
    }

    fun connectWithKey(key: String) {
        val provider = _state.value.selected ?: return
        _state.update { it.copy(busy = true, sheetError = null) }
        viewModelScope.launch {
            when (val result = repository.setApiKey(provider.id, key)) {
                is ApiResult.Ok -> succeed(UiText.of(R.string.providers_msg_connected, provider.name))
                is ApiResult.Err -> _state.update { it.copy(busy = false, sheetError = UiText.raw(result.message)) }
            }
        }
    }

    fun startOAuth(method: ProviderAuthMethod.OAuth, inputs: Map<String, String>) {
        val provider = _state.value.selected ?: return
        _state.update { it.copy(busy = true, sheetError = null) }
        viewModelScope.launch {
            when (val result = repository.startOAuth(provider.id, method, inputs)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(busy = false, oauth = result.value) }
                    // Automatic flows finish on their own once the user approves in the browser.
                    if (!result.value.needsCode) finishOAuth(null)
                }

                is ApiResult.Err -> _state.update { it.copy(busy = false, sheetError = UiText.raw(result.message)) }
            }
        }
    }

    fun finishOAuth(code: String?) {
        val session = _state.value.oauth ?: return
        val provider = _state.value.selected ?: return
        oauthJob?.cancel()
        _state.update { it.copy(busy = true, sheetError = null) }
        oauthJob = viewModelScope.launch {
            when (val result = repository.finishOAuth(session, code)) {
                is ApiResult.Ok -> succeed(UiText.of(R.string.providers_msg_connected, provider.name))
                is ApiResult.Err -> _state.update { it.copy(busy = false, sheetError = UiText.raw(result.message)) }
            }
        }
    }

    fun disconnect(provider: ProviderInfo) {
        _state.update { it.copy(busy = true, sheetError = null) }
        viewModelScope.launch {
            when (val result = repository.disconnect(provider)) {
                is ApiResult.Ok -> succeed(UiText.of(R.string.providers_msg_disconnected, provider.name))
                is ApiResult.Err -> _state.update { it.copy(busy = false, sheetError = UiText.raw(result.message)) }
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun succeed(message: UiText) {
        _state.update { it.copy(busy = false, selectedId = null, oauth = null, message = message) }
        load()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { ProvidersViewModel(container.providerRepository, container.connectionManager) }
        }
    }
}

data class CustomProviderUiState(
    val saving: Boolean = false,
    val error: UiText? = null,
    val done: Boolean = false,
    val message: UiText? = null,
)

class CustomProviderViewModel(private val repository: ProviderRepository) : ViewModel() {

    private val _state = MutableStateFlow(CustomProviderUiState())
    val state: StateFlow<CustomProviderUiState> = _state.asStateFlow()

    fun save(draft: CustomProviderDraft) {
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.addCustomProvider(draft)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(saving = false, done = true, message = UiText.of(R.string.providers_msg_custom_added, draft.name.ifBlank { draft.id }))
                }

                is ApiResult.Err -> _state.update { it.copy(saving = false, error = UiText.raw(result.message)) }
            }
        }
    }

    companion object {
        val ID_PATTERN = Regex("^[a-z0-9][a-z0-9-]{1,39}$")

        /** `My Local LLM` → `my-local-llm`, the id suggested while the user types a name. */
        fun slug(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CustomProviderViewModel(container.providerRepository) }
        }
    }
}
