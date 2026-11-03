package ai.opencode.android.ui.settings

import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.AppearanceSettings
import ai.opencode.android.settings.ExtraSettingsCodec
import ai.opencode.android.settings.SavedTheme
import ai.opencode.android.settings.UiCustomization
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

    fun setCustom(transform: (UiCustomization) -> UiCustomization) = edit { it.copy(custom = transform(it.custom).normalized()) }

    fun resetCustom() = edit { it.copy(custom = UiCustomization()) }

    /** Stores the current palette, mode and customisation under [name] (replacing a theme of that name). */
    fun saveTheme(name: String) = edit { current ->
        val theme = SavedTheme(
            id = current.savedThemes.firstOrNull { it.name == name }?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            palette = current.palette,
            themeMode = current.themeMode,
            pureBlack = current.pureBlack,
            custom = current.custom,
        )
        current.copy(savedThemes = current.savedThemes.filterNot { it.id == theme.id } + theme)
    }

    fun applyTheme(theme: SavedTheme) = edit {
        it.copy(palette = theme.palette, themeMode = theme.themeMode, pureBlack = theme.pureBlack, custom = theme.custom.normalized())
    }

    fun deleteTheme(id: String) = edit { it.copy(savedThemes = it.savedThemes.filterNot { theme -> theme.id == id }) }

    /** The current look as a shareable JSON file. */
    fun exportCurrent(name: String, current: AppearanceSettings): String = ExtraSettingsCodec.exportTheme(
        SavedTheme(java.util.UUID.randomUUID().toString(), name, current.palette, current.themeMode, current.pureBlack, current.custom),
    )

    /** Parses a theme file, saves it and applies it; throws on anything that is not a theme. */
    fun importTheme(raw: String): SavedTheme {
        val parsed = ExtraSettingsCodec.importTheme(raw)
        val theme = parsed.copy(id = java.util.UUID.randomUUID().toString(), name = parsed.name.ifBlank { "Imported" })
        edit {
            it.copy(
                savedThemes = it.savedThemes + theme,
                palette = theme.palette, themeMode = theme.themeMode, pureBlack = theme.pureBlack, custom = theme.custom,
            )
        }
        return theme
    }

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
