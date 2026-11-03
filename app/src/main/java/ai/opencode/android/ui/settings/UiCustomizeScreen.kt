package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.settings.AppearanceSettings
import ai.opencode.android.settings.FontChoice
import ai.opencode.android.settings.SavedTheme
import ai.opencode.android.settings.UiCustomization
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import ai.opencode.android.ui.components.SettingsSwitchItem
import ai.opencode.android.ui.theme.ColorGroup
import ai.opencode.android.ui.theme.ColorRole
import ai.opencode.android.ui.theme.ColorRoles
import ai.opencode.android.ui.theme.LocalDarkTheme
import ai.opencode.android.ui.theme.LocalExtraColors
import ai.opencode.android.ui.theme.csp
import ai.opencode.android.ui.theme.family
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/**
 * Settings → Appearance → Fine-tuning: every colour (separately for light and dark), text sizes,
 * fonts, corner radii, spacing, which chat elements are shown, and named themes that can be saved,
 * shared as JSON files and imported.
 */
@Composable
fun UiCustomizeScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: AppearanceViewModel = viewModel(factory = AppearanceViewModel.factory(container))
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val current = appearance ?: return
    val custom = current.custom
    val context = LocalContext.current
    val systemDark = LocalDarkTheme.current
    var editDark by rememberSaveable { mutableStateOf(systemDark) }
    var editing by remember { mutableStateOf<ColorRole?>(null) }
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var pasteDialog by rememberSaveable { mutableStateOf(false) }
    var resetDialog by rememberSaveable { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri != null && text != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("no stream")
            }.isSuccess
            toast(context, if (ok) R.string.ui_theme_exported else R.string.ui_theme_export_failed)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("no stream")
                viewModel.importTheme(text)
            }
            result.onSuccess { toast(context, context.getString(R.string.ui_theme_imported, it.name)) }
                .onFailure { toast(context, R.string.ui_theme_import_failed) }
        }
    }

    SettingsPage(title = stringResource(R.string.ui_custom_title), onBack = onBack) {
        LivePreview()

        // ------------------------------------------------------------------ themes
        SettingsGroup(title = stringResource(R.string.ui_themes_group)) {
            current.savedThemes.forEach { theme ->
                SettingsItem(
                    title = theme.name,
                    subtitle = stringResource(R.string.ui_theme_subtitle, theme.palette.name.lowercase(), theme.custom.overrideCount()),
                    icon = Icons.Default.Palette,
                    onClick = { viewModel.applyTheme(theme) },
                    trailing = {
                        IconButton(onClick = {
                            pendingExport = ai.opencode.android.settings.ExtraSettingsCodec.exportTheme(theme)
                            exportLauncher.launch("${theme.name.fileSafe()}.opencode-theme.json")
                        }) { Icon(Icons.Default.SaveAs, contentDescription = stringResource(R.string.ui_theme_export)) }
                        IconButton(onClick = { viewModel.deleteTheme(theme.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ui_theme_delete))
                        }
                    },
                )
                SettingsDivider()
            }
            SettingsItem(
                title = stringResource(R.string.ui_theme_save),
                subtitle = stringResource(R.string.ui_theme_save_hint),
                icon = Icons.Default.Save,
                onClick = { saveDialog = true },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.ui_theme_export_current),
                icon = Icons.Default.SaveAs,
                onClick = {
                    pendingExport = viewModel.exportCurrent(context.getString(R.string.ui_theme_default_name), current)
                    exportLauncher.launch("opencode-theme.json")
                },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.ui_theme_import_file),
                icon = Icons.Default.FileOpen,
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.ui_theme_import_paste),
                icon = Icons.Default.ContentCopy,
                onClick = { pasteDialog = true },
            )
        }

        // ------------------------------------------------------------------ text
        SettingsGroup(title = stringResource(R.string.ui_text_group)) {
            SliderRow(stringResource(R.string.ui_font_scale_ui), custom.uiFontScale, 0.7f..1.6f, percent = true) { v -> viewModel.setCustom { it.copy(uiFontScale = v) } }
            SliderRow(stringResource(R.string.ui_font_scale_chat), custom.chatFontScale, 0.6f..2f, percent = true) { v -> viewModel.setCustom { it.copy(chatFontScale = v) } }
            SliderRow(stringResource(R.string.ui_font_scale_code), custom.codeFontScale, 0.6f..2f, percent = true) { v -> viewModel.setCustom { it.copy(codeFontScale = v) } }
            SliderRow(stringResource(R.string.ui_line_height), custom.lineHeightScale, 0.8f..1.8f, percent = true) { v -> viewModel.setCustom { it.copy(lineHeightScale = v) } }
            FontRow(stringResource(R.string.ui_font_ui), custom.uiFont) { f -> viewModel.setCustom { it.copy(uiFont = f) } }
            FontRow(stringResource(R.string.ui_font_chat), custom.chatFont) { f -> viewModel.setCustom { it.copy(chatFont = f) } }
        }

        // ------------------------------------------------------------------ shape & spacing
        SettingsGroup(title = stringResource(R.string.ui_shape_group)) {
            SliderRow(stringResource(R.string.ui_corner_scale), custom.cornerScale, 0f..2.5f, percent = true) { v -> viewModel.setCustom { it.copy(cornerScale = v) } }
            SliderRow(stringResource(R.string.ui_spacing_scale), custom.spacingScale, 0.3f..2.5f, percent = true) { v -> viewModel.setCustom { it.copy(spacingScale = v) } }
            SliderRow(stringResource(R.string.ui_bubble_radius), custom.bubbleRadiusDp.toFloat(), 0f..40f, unit = "dp") { v -> viewModel.setCustom { it.copy(bubbleRadiusDp = v.roundToInt()) } }
            SliderRow(stringResource(R.string.ui_bubble_width), custom.bubbleMaxWidthDp.toFloat(), 200f..1600f, unit = "dp", step = 20f) { v -> viewModel.setCustom { it.copy(bubbleMaxWidthDp = v.roundToInt()) } }
            SliderRow(stringResource(R.string.ui_content_width), custom.contentMaxWidthDp.toFloat(), 360f..2400f, unit = "dp", step = 20f) { v -> viewModel.setCustom { it.copy(contentMaxWidthDp = v.roundToInt()) } }
        }

        // ------------------------------------------------------------------ elements
        SettingsGroup(title = stringResource(R.string.ui_elements_group)) {
            SettingsSwitchItem(
                title = stringResource(R.string.ui_show_status_line),
                subtitle = stringResource(R.string.ui_show_status_line_hint),
                icon = Icons.Default.ShortText,
                checked = custom.showStatusLine,
                onCheckedChange = { v -> viewModel.setCustom { it.copy(showStatusLine = v) } },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.ui_compact_bars),
                subtitle = stringResource(R.string.ui_compact_bars_hint),
                icon = Icons.Default.ViewCompact,
                checked = custom.compactTopBars,
                onCheckedChange = { v -> viewModel.setCustom { it.copy(compactTopBars = v) } },
            )
            SettingsDivider()
            SettingsSwitchItem(
                title = stringResource(R.string.ui_animations),
                icon = Icons.Default.Animation,
                checked = custom.animations,
                onCheckedChange = { v -> viewModel.setCustom { it.copy(animations = v) } },
            )
        }

        // ------------------------------------------------------------------ colours
        SettingsGroup(title = stringResource(R.string.ui_colors_group)) {
            Text(
                text = stringResource(R.string.ui_colors_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                listOf(false, true).forEachIndexed { index, dark ->
                    SegmentedButton(
                        selected = editDark == dark,
                        onClick = { editDark = dark },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(if (dark) R.string.ui_colors_dark else R.string.ui_colors_light)) }
                }
            }
            if (editDark != systemDark) {
                Text(
                    text = stringResource(R.string.ui_colors_other_mode),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val overrides = custom.colors(editDark)
            val scheme = MaterialTheme.colorScheme
            val extra = LocalExtraColors.current
            ColorGroup.entries.forEach { group ->
                Text(
                    text = stringResource(group.title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                ColorRoles.all.filter { it.group == group }.forEach { role ->
                    val override = overrides[role.key]
                    // Live colours are those of the mode on screen; for the other mode show the override only.
                    val shown = override?.let { Color(it.toInt()) } ?: if (editDark == systemDark) role.read(scheme, extra) else null
                    ColorRow(
                        title = stringResource(role.title),
                        color = shown,
                        changed = override != null,
                        onClick = { editing = role },
                        onReset = { viewModel.setCustom { it.withColor(editDark, role.key, null) } },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        SettingsGroup {
            SettingsItem(
                title = stringResource(R.string.ui_reset_all),
                subtitle = stringResource(R.string.ui_reset_all_hint),
                icon = Icons.Default.RestartAlt,
                onClick = { resetDialog = true },
            )
        }
    }

    editing?.let { role ->
        val overrides = custom.colors(editDark)
        val initial = overrides[role.key]?.let { Color(it.toInt()) }
            ?: if (editDark == systemDark) role.read(MaterialTheme.colorScheme, LocalExtraColors.current) else MaterialTheme.colorScheme.primary
        ColorPickerDialog(
            title = stringResource(role.title),
            initial = initial,
            onDismiss = { editing = null },
            onReset = {
                viewModel.setCustom { it.withColor(editDark, role.key, null) }
                editing = null
            },
            onPick = { color ->
                viewModel.setCustom { it.withColor(editDark, role.key, ColorRoles.argb(color)) }
                editing = null
            },
        )
    }

    if (saveDialog) {
        TextInputDialog(
            title = stringResource(R.string.ui_theme_save),
            label = stringResource(R.string.ui_theme_name),
            initial = "",
            onDismiss = { saveDialog = false },
            onConfirm = { name ->
                saveDialog = false
                if (name.isNotBlank()) viewModel.saveTheme(name)
            },
        )
    }

    if (pasteDialog) {
        TextInputDialog(
            title = stringResource(R.string.ui_theme_import_paste),
            label = stringResource(R.string.ui_theme_json),
            initial = "",
            singleLine = false,
            onDismiss = { pasteDialog = false },
            onConfirm = { text ->
                pasteDialog = false
                runCatching { viewModel.importTheme(text) }
                    .onSuccess { toast(context, context.getString(R.string.ui_theme_imported, it.name)) }
                    .onFailure { toast(context, R.string.ui_theme_import_failed) }
            },
        )
    }

    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            title = { Text(stringResource(R.string.ui_reset_all)) },
            text = { Text(stringResource(R.string.ui_reset_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    resetDialog = false
                    viewModel.resetCustom()
                }) { Text(stringResource(R.string.ui_reset)) }
            },
            dismissButton = { TextButton(onClick = { resetDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun UiCustomization.overrideCount(): Int = light.size + dark.size

private fun String.fileSafe(): String = replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "theme" }

private fun toast(context: Context, res: Int) = Toast.makeText(context, res, Toast.LENGTH_SHORT).show()

private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** A slice of chat, code and diff that re-renders with every change on this screen. */
@Composable
private fun LivePreview() {
    val extra = LocalExtraColors.current
    Surface(
        color = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ai.opencode.android.ui.chat.MessagePreview()
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                Column(Modifier.padding(8.dp)) {
                    Text("fun greet() = \"hi\" // 42", fontFamily = FontFamily.Monospace, fontSize = 12.5.csp, color = extra.syntaxKeyword)
                    Text("+ added line", fontFamily = FontFamily.Monospace, fontSize = 12.5.csp, color = extra.diffAddedText, modifier = Modifier.fillMaxWidth().background(extra.diffAddedBackground))
                    Text("- removed line", fontFamily = FontFamily.Monospace, fontSize = 12.5.csp, color = extra.diffRemovedText, modifier = Modifier.fillMaxWidth().background(extra.diffRemovedBackground))
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    percent: Boolean = false,
    unit: String = "",
    step: Float = 0f,
    onCommit: (Float) -> Unit,
) {
    // Local while dragging, committed once: every DataStore write re-themes the whole app.
    var dragging by remember(value) { mutableFloatStateOf(value) }
    val label = if (percent) "${(dragging * 100).roundToInt()}%" else "${dragging.roundToInt()} $unit".trim()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = dragging,
            onValueChange = { v -> dragging = if (step > 0f) (v / step).roundToInt() * step else v },
            valueRange = range,
            onValueChangeFinished = { onCommit(dragging) },
        )
    }
}

@Composable
private fun FontRow(title: String, selected: FontChoice, onSelect: (FontChoice) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FontChoice.entries.forEach { font ->
                FilterChip(
                    selected = font == selected,
                    onClick = { onSelect(font) },
                    label = {
                        Text(
                            text = stringResource(
                                when (font) {
                                    FontChoice.SYSTEM -> R.string.ui_font_system
                                    FontChoice.SANS -> R.string.ui_font_sans
                                    FontChoice.SERIF -> R.string.ui_font_serif
                                    FontChoice.MONO -> R.string.ui_font_mono
                                },
                            ),
                            fontFamily = font.family(),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorRow(title: String, color: Color?, changed: Boolean, onClick: () -> Unit, onReset: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .padding(2.dp)
                .background(color ?: Color.Transparent, CircleShape),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = color?.let { ColorRoles.hex(ColorRoles.argb(it)) } ?: stringResource(R.string.ui_color_palette_value),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (changed) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.ui_reset)) }
        }
    }
}

/** HSV + alpha sliders, a hex field and quick swatches. */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onPick: (Color) -> Unit,
) {
    val hsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember(initial) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(initial) { mutableFloatStateOf(hsv[1]) }
    var value by remember(initial) { mutableFloatStateOf(hsv[2]) }
    var alpha by remember(initial) { mutableFloatStateOf(initial.alpha) }
    val color = Color.hsv(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
    var hex by remember(initial) { mutableStateOf(ColorRoles.hex(ColorRoles.argb(initial))) }
    LaunchedEffect(color) {
        val current = ColorRoles.parseHex(hex)
        if (current == null || current != ColorRoles.argb(color)) hex = ColorRoles.hex(ColorRoles.argb(color))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .background(color, RoundedCornerShape(12.dp)),
                )
                GradientSlider(stringResource(R.string.ui_color_hue), hue, 0f..360f, Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) })) { hue = it }
                GradientSlider(stringResource(R.string.ui_color_saturation), saturation, 0f..1f, Brush.horizontalGradient(listOf(Color.hsv(hue, 0f, value), Color.hsv(hue, 1f, value)))) { saturation = it }
                GradientSlider(stringResource(R.string.ui_color_brightness), value, 0f..1f, Brush.horizontalGradient(listOf(Color.Black, Color.hsv(hue, saturation, 1f)))) { value = it }
                GradientSlider(stringResource(R.string.ui_color_alpha), alpha, 0f..1f, Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = 1f)))) { alpha = it }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text
                        ColorRoles.parseHex(text)?.let { argb ->
                            val parsed = Color(argb.toInt())
                            val out = FloatArray(3)
                            android.graphics.Color.colorToHSV(parsed.toArgb(), out)
                            hue = out[0]; saturation = out[1]; value = out[2]; alpha = parsed.alpha
                        }
                    },
                    label = { Text("HEX") },
                    singleLine = true,
                    isError = ColorRoles.parseHex(hex) == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QUICK_SWATCHES.forEach { swatch ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .background(swatch, CircleShape)
                                .clickable {
                                    val out = FloatArray(3)
                                    android.graphics.Color.colorToHSV(swatch.toArgb(), out)
                                    hue = out[0]; saturation = out[1]; value = out[2]; alpha = 1f
                                },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(stringResource(R.string.ui_apply)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(R.string.ui_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun GradientSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, brush: Brush, onChange: (Float) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().height(10.dp).background(brush, RoundedCornerShape(5.dp)))
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/** One text field in a dialog; used for theme names and pasted JSON. */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 6,
                maxLines = if (singleLine) 1 else 14,
                textStyle = if (singleLine) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.ui_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Copies [text] and confirms with a toast. */
fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}

private val QUICK_SWATCHES = listOf(
    Color(0xFFD97757), Color(0xFFC96442), Color(0xFFE0A63A), Color(0xFF3FB27F), Color(0xFF7DD3FC),
    Color(0xFF4C8DFF), Color(0xFF8E6CF0), Color(0xFFE0607E), Color(0xFFFFFFFF), Color(0xFFF0EEE6),
    Color(0xFF9E9E9E), Color(0xFF30302E), Color(0xFF262624), Color(0xFF0E1014), Color(0xFF000000),
)
