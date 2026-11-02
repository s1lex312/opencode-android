package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.TextScale
import ai.opencode.android.settings.ThemeMode
import ai.opencode.android.settings.ThemePalette
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsRadioItem
import ai.opencode.android.ui.components.SettingsSwitchItem
import ai.opencode.android.ui.i18n.AppLanguage
import ai.opencode.android.ui.theme.LocalChatTextScale
import ai.opencode.android.ui.theme.LocalDarkTheme
import ai.opencode.android.ui.theme.dynamicColorAvailable
import ai.opencode.android.ui.theme.paletteSwatch
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Settings → Appearance: theme, accent, OLED black, chat text size and the interface language. */
@Composable
fun AppearanceSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: AppearanceViewModel = viewModel(factory = AppearanceViewModel.factory(container))
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    var languageDialog by rememberSaveable { mutableStateOf(false) }
    val current = appearance ?: return

    SettingsPage(title = stringResource(R.string.settings_appearance_title), onBack = onBack) {
        PreviewCard()

        SettingsGroup(title = stringResource(R.string.settings_theme_group)) {
            Choice(
                options = ThemeMode.entries,
                selected = current.themeMode,
                label = {
                    stringResource(
                        when (it) {
                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                            ThemeMode.LIGHT -> R.string.settings_theme_light
                            ThemeMode.DARK -> R.string.settings_theme_dark
                        },
                    )
                },
                onSelect = viewModel::setThemeMode,
            )
            Text(
                text = stringResource(R.string.settings_palette),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            val dark = LocalDarkTheme.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemePalette.entries
                    .filter { it != ThemePalette.DYNAMIC || dynamicColorAvailable }
                    .forEach { palette ->
                        Swatch(
                            color = if (palette == ThemePalette.DYNAMIC) {
                                MaterialTheme.colorScheme.primary.takeIf { current.palette == palette }
                                    ?: MaterialTheme.colorScheme.tertiary
                            } else {
                                paletteSwatch(palette, dark)
                            },
                            label = stringResource(paletteLabel(palette)),
                            selected = current.palette == palette,
                            onClick = { viewModel.setPalette(palette) },
                        )
                    }
            }
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.settings_pure_black),
                subtitle = stringResource(
                    if (dark) R.string.settings_pure_black_subtitle else R.string.settings_pure_black_disabled,
                ),
                icon = Icons.Default.Contrast,
                checked = current.pureBlack,
                enabled = dark,
                onCheckedChange = viewModel::setPureBlack,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_chat_group)) {
            Text(
                text = stringResource(R.string.settings_text_size),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            Choice(
                options = TextScale.entries,
                selected = current.chatTextScale,
                label = {
                    stringResource(
                        when (it) {
                            TextScale.SMALL -> R.string.settings_text_small
                            TextScale.DEFAULT -> R.string.settings_text_default
                            TextScale.LARGE -> R.string.settings_text_large
                        },
                    )
                },
                onSelect = viewModel::setChatTextScale,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_language_group)) {
            SettingsItem(
                title = stringResource(R.string.settings_language),
                subtitle = languageLabel(AppLanguage.current()),
                icon = Icons.Default.Language,
                showChevron = true,
                onClick = { languageDialog = true },
            )
        }
    }

    if (languageDialog) {
        val selected = AppLanguage.current()
        AlertDialog(
            onDismissRequest = { languageDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = stringResource(R.string.settings_language_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    AppLanguage.entries.forEach { language ->
                        SettingsRadioItem(
                            title = languageLabel(language),
                            selected = language == selected,
                            onClick = {
                                languageDialog = false
                                if (language != selected) AppLanguage.apply(language)
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { languageDialog = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

private fun paletteLabel(palette: ThemePalette): Int = when (palette) {
    ThemePalette.OPENCODE -> R.string.settings_palette_opencode
    ThemePalette.DYNAMIC -> R.string.settings_palette_dynamic
    ThemePalette.EMERALD -> R.string.settings_palette_emerald
    ThemePalette.AMBER -> R.string.settings_palette_amber
    ThemePalette.VIOLET -> R.string.settings_palette_violet
    ThemePalette.ROSE -> R.string.settings_palette_rose
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(4.dp)
            .semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    shape = CircleShape,
                )
                .padding(if (selected) 5.dp else 0.dp),
        ) {
            Surface(shape = CircleShape, color = color, modifier = Modifier.size(if (selected) 38.dp else 48.dp)) {
                if (selected) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A miniature conversation that re-themes live while the user tweaks the options below. */
@Composable
private fun PreviewCard() {
    val scale = LocalChatTextScale.current
    val body = MaterialTheme.typography.bodyMedium.let {
        it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale)
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_preview_user),
                        style = body,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) {}
                Box(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_preview_assistant),
                    style = body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
