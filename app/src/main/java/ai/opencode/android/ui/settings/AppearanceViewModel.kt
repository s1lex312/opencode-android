package ai.opencode.android.ui.settings

import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.AppearanceSettings
import ai.opencode.android.settings.SettingsRepository
import ai.opencode.android.settings.TextScale
import ai.opencode.android.settings.ThemeMode
import ai.opencode.android.settings.ThemePalette
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings → Appearance. Every change is persisted immediately; `MainActivity` observes the same
 * DataStore flow, so the whole app (including this screen's preview) re-themes live.
 *
 * The interface language is not here: AppCompat owns it (see `AppLanguage`).
 */
class AppearanceViewModel(private val settingsRepository: SettingsRepository) : ViewModel() {

    /** Null until DataStore has answered, so the screen never flashes default values. */
    val appearance: StateFlow<AppearanceSettings?> = settingsRepository.settings
        .map { it.appearance }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setThemeMode(mode: ThemeMode) = edit { it.copy(themeMode = mode) }

    fun setPalette(palette: ThemePalette) = edit { it.copy(palette = palette) }

    fun setPureBlack(enabled: Boolean) = edit { it.copy(pureBlack = enabled) }

    fun setChatTextScale(scale: TextScale) = edit { it.copy(chatTextScale = scale) }

    private fun edit(transform: (AppearanceSettings) -> AppearanceSettings) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(appearance = transform(it.appearance)) }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { AppearanceViewModel(container.settingsRepository) }
        }
    }
}
