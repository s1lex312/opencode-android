package ai.opencode.android.ui.theme

import ai.opencode.android.R
import ai.opencode.android.settings.UiCustomization
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Multiplier for monospace text: code blocks, tool output, diffs, logs. */
val LocalCodeTextScale = staticCompositionLocalOf { 1f }

/** `12.5.csp`: a monospace size that follows Settings → Appearance → Code size. */
val Double.csp: TextUnit
    @Composable @ReadOnlyComposable
    get() = (this * LocalCodeTextScale.current).sp

val Int.csp: TextUnit
    @Composable @ReadOnlyComposable
    get() = (this * LocalCodeTextScale.current).sp

/** Colours outside Material's scheme: diffs, syntax, status dots, context meter. */
@Immutable
data class ExtraColors(
    val diffAddedBackground: Color,
    val diffAddedText: Color,
    val diffRemovedBackground: Color,
    val diffRemovedText: Color,
    val diffHunkBackground: Color,
    val diffHunkText: Color,
    val syntaxKeyword: Color,
    val syntaxString: Color,
    val syntaxComment: Color,
    val syntaxNumber: Color,
    val statusOk: Color,
    val statusWarn: Color,
    val contextInput: Color,
    val contextCacheRead: Color,
    val contextCacheWrite: Color,
    val contextOutput: Color,
    val contextReasoning: Color,
) {
    companion object {
        fun defaults(dark: Boolean, scheme: ColorScheme): ExtraColors = ExtraColors(
            diffAddedBackground = if (dark) Color(0xFF122B1D) else Color(0xFFE6F6EA),
            diffAddedText = if (dark) Color(0xFF9BE9A8) else Color(0xFF1B5E20),
            diffRemovedBackground = if (dark) Color(0xFF341A1A) else Color(0xFFFDECEA),
            diffRemovedText = if (dark) Color(0xFFFFB3AD) else Color(0xFFB3261E),
            diffHunkBackground = if (dark) Color(0xFF16222E) else Color(0xFFE8F1F8),
            diffHunkText = if (dark) Color(0xFF8FC7E8) else Color(0xFF0B4F6C),
            syntaxKeyword = scheme.primary,
            syntaxString = if (dark) Color(0xFF7FB069) else Color(0xFF2E7D32),
            syntaxComment = scheme.onSurfaceVariant,
            syntaxNumber = scheme.tertiary,
            statusOk = Color(0xFF3FB27F),
            statusWarn = Color(0xFFE0A63A),
            contextInput = Color(0xFF4C8DFF),
            contextCacheRead = Color(0xFF35B57A),
            contextCacheWrite = Color(0xFF8E6CF0),
            contextOutput = Color(0xFFE0A030),
            contextReasoning = Color(0xFFE0607E),
        )
    }
}

val LocalExtraColors = staticCompositionLocalOf { ExtraColors.defaults(false, androidx.compose.material3.lightColorScheme()) }

/** Sizes and switches from [UiCustomization] that individual composables read. */
@Immutable
data class UiTokens(
    val spacing: Float = 1f,
    val bubbleRadius: Dp = 22.dp,
    val bubbleMaxWidth: Dp = 640.dp,
    val contentMaxWidth: Dp = 840.dp,
    val showStatusLine: Boolean = true,
    /** Chat prose font; null keeps the UI font. */
    val chatFont: androidx.compose.ui.text.font.FontFamily? = null,
    val lineHeight: Float = 1f,
    val compactTopBars: Boolean = false,
    val animations: Boolean = true,
) {
    /** [base] dp scaled by the spacing setting. */
    fun gap(base: Float): Dp = (base * spacing).dp

    companion object {
        fun from(custom: UiCustomization) = UiTokens(
            spacing = custom.spacingScale,
            bubbleRadius = custom.bubbleRadiusDp.dp,
            bubbleMaxWidth = custom.bubbleMaxWidthDp.dp,
            contentMaxWidth = custom.contentMaxWidthDp.dp,
            showStatusLine = custom.showStatusLine,
            chatFont = custom.chatFont.takeIf { it != ai.opencode.android.settings.FontChoice.SYSTEM }?.family(),
            lineHeight = custom.lineHeightScale,
            compactTopBars = custom.compactTopBars,
            animations = custom.animations,
        )
    }
}

val LocalUiTokens = staticCompositionLocalOf { UiTokens() }

enum class ColorGroup(@StringRes val title: Int) {
    ACCENT(R.string.ui_colors_group_accent),
    SURFACE(R.string.ui_colors_group_surface),
    TEXT(R.string.ui_colors_group_text),
    STATE(R.string.ui_colors_group_state),
    CODE(R.string.ui_colors_group_code),
    CONTEXT(R.string.ui_colors_group_context),
}

/** One user-editable colour: [key] is what [UiCustomization] stores. */
data class ColorRole(
    val key: String,
    @StringRes val title: Int,
    val group: ColorGroup,
    val read: (ColorScheme, ExtraColors) -> Color,
)

/** Every colour the appearance editor offers, and how overrides are applied. */
object ColorRoles {

    private fun scheme(key: String, title: Int, group: ColorGroup, read: (ColorScheme) -> Color) =
        ColorRole(key, title, group) { s, _ -> read(s) }

    private fun extra(key: String, title: Int, group: ColorGroup, read: (ExtraColors) -> Color) =
        ColorRole(key, title, group) { _, e -> read(e) }

    val all: List<ColorRole> = listOf(
        scheme("primary", R.string.ui_color_primary, ColorGroup.ACCENT) { it.primary },
        scheme("onPrimary", R.string.ui_color_on_primary, ColorGroup.ACCENT) { it.onPrimary },
        scheme("primaryContainer", R.string.ui_color_primary_container, ColorGroup.ACCENT) { it.primaryContainer },
        scheme("onPrimaryContainer", R.string.ui_color_on_primary_container, ColorGroup.ACCENT) { it.onPrimaryContainer },
        scheme("secondary", R.string.ui_color_secondary, ColorGroup.ACCENT) { it.secondary },
        scheme("secondaryContainer", R.string.ui_color_secondary_container, ColorGroup.ACCENT) { it.secondaryContainer },
        scheme("onSecondaryContainer", R.string.ui_color_on_secondary_container, ColorGroup.ACCENT) { it.onSecondaryContainer },
        scheme("tertiary", R.string.ui_color_tertiary, ColorGroup.ACCENT) { it.tertiary },
        scheme("tertiaryContainer", R.string.ui_color_tertiary_container, ColorGroup.ACCENT) { it.tertiaryContainer },
        scheme("background", R.string.ui_color_background, ColorGroup.SURFACE) { it.background },
        scheme("surface", R.string.ui_color_surface, ColorGroup.SURFACE) { it.surface },
        scheme("surfaceVariant", R.string.ui_color_surface_variant, ColorGroup.SURFACE) { it.surfaceVariant },
        scheme("surfaceContainerLowest", R.string.ui_color_container_lowest, ColorGroup.SURFACE) { it.surfaceContainerLowest },
        scheme("surfaceContainerLow", R.string.ui_color_container_low, ColorGroup.SURFACE) { it.surfaceContainerLow },
        scheme("surfaceContainer", R.string.ui_color_container, ColorGroup.SURFACE) { it.surfaceContainer },
        scheme("surfaceContainerHigh", R.string.ui_color_container_high, ColorGroup.SURFACE) { it.surfaceContainerHigh },
        scheme("surfaceContainerHighest", R.string.ui_color_container_highest, ColorGroup.SURFACE) { it.surfaceContainerHighest },
        scheme("outline", R.string.ui_color_outline, ColorGroup.SURFACE) { it.outline },
        scheme("outlineVariant", R.string.ui_color_outline_variant, ColorGroup.SURFACE) { it.outlineVariant },
        scheme("onBackground", R.string.ui_color_on_background, ColorGroup.TEXT) { it.onBackground },
        scheme("onSurface", R.string.ui_color_on_surface, ColorGroup.TEXT) { it.onSurface },
        scheme("onSurfaceVariant", R.string.ui_color_on_surface_variant, ColorGroup.TEXT) { it.onSurfaceVariant },
        scheme("inverseSurface", R.string.ui_color_inverse_surface, ColorGroup.TEXT) { it.inverseSurface },
        scheme("inverseOnSurface", R.string.ui_color_inverse_on_surface, ColorGroup.TEXT) { it.inverseOnSurface },
        scheme("error", R.string.ui_color_error, ColorGroup.STATE) { it.error },
        scheme("errorContainer", R.string.ui_color_error_container, ColorGroup.STATE) { it.errorContainer },
        scheme("onErrorContainer", R.string.ui_color_on_error_container, ColorGroup.STATE) { it.onErrorContainer },
        extra("statusOk", R.string.ui_color_status_ok, ColorGroup.STATE) { it.statusOk },
        extra("statusWarn", R.string.ui_color_status_warn, ColorGroup.STATE) { it.statusWarn },
        extra("diffAddedBackground", R.string.ui_color_diff_added_bg, ColorGroup.CODE) { it.diffAddedBackground },
        extra("diffAddedText", R.string.ui_color_diff_added_text, ColorGroup.CODE) { it.diffAddedText },
        extra("diffRemovedBackground", R.string.ui_color_diff_removed_bg, ColorGroup.CODE) { it.diffRemovedBackground },
        extra("diffRemovedText", R.string.ui_color_diff_removed_text, ColorGroup.CODE) { it.diffRemovedText },
        extra("diffHunkBackground", R.string.ui_color_diff_hunk_bg, ColorGroup.CODE) { it.diffHunkBackground },
        extra("diffHunkText", R.string.ui_color_diff_hunk_text, ColorGroup.CODE) { it.diffHunkText },
        extra("syntaxKeyword", R.string.ui_color_syntax_keyword, ColorGroup.CODE) { it.syntaxKeyword },
        extra("syntaxString", R.string.ui_color_syntax_string, ColorGroup.CODE) { it.syntaxString },
        extra("syntaxComment", R.string.ui_color_syntax_comment, ColorGroup.CODE) { it.syntaxComment },
        extra("syntaxNumber", R.string.ui_color_syntax_number, ColorGroup.CODE) { it.syntaxNumber },
        extra("contextInput", R.string.ui_color_ctx_input, ColorGroup.CONTEXT) { it.contextInput },
        extra("contextCacheRead", R.string.ui_color_ctx_cache_read, ColorGroup.CONTEXT) { it.contextCacheRead },
        extra("contextCacheWrite", R.string.ui_color_ctx_cache_write, ColorGroup.CONTEXT) { it.contextCacheWrite },
        extra("contextOutput", R.string.ui_color_ctx_output, ColorGroup.CONTEXT) { it.contextOutput },
        extra("contextReasoning", R.string.ui_color_ctx_reasoning, ColorGroup.CONTEXT) { it.contextReasoning },
    )

    private fun Map<String, Long>.color(key: String): Color? = this[key]?.let { Color(it.toInt()) }

    fun apply(scheme: ColorScheme, overrides: Map<String, Long>): ColorScheme {
        if (overrides.isEmpty()) return scheme
        val o = overrides
        val background = o.color("background") ?: scheme.background
        return scheme.copy(
            primary = o.color("primary") ?: scheme.primary,
            onPrimary = o.color("onPrimary") ?: scheme.onPrimary,
            primaryContainer = o.color("primaryContainer") ?: scheme.primaryContainer,
            onPrimaryContainer = o.color("onPrimaryContainer") ?: scheme.onPrimaryContainer,
            surfaceTint = o.color("primary") ?: scheme.surfaceTint,
            secondary = o.color("secondary") ?: scheme.secondary,
            secondaryContainer = o.color("secondaryContainer") ?: scheme.secondaryContainer,
            onSecondaryContainer = o.color("onSecondaryContainer") ?: scheme.onSecondaryContainer,
            tertiary = o.color("tertiary") ?: scheme.tertiary,
            tertiaryContainer = o.color("tertiaryContainer") ?: scheme.tertiaryContainer,
            background = background,
            surface = o.color("surface") ?: o.color("background") ?: scheme.surface,
            surfaceDim = o.color("background") ?: scheme.surfaceDim,
            surfaceVariant = o.color("surfaceVariant") ?: scheme.surfaceVariant,
            surfaceContainerLowest = o.color("surfaceContainerLowest") ?: scheme.surfaceContainerLowest,
            surfaceContainerLow = o.color("surfaceContainerLow") ?: scheme.surfaceContainerLow,
            surfaceContainer = o.color("surfaceContainer") ?: scheme.surfaceContainer,
            surfaceContainerHigh = o.color("surfaceContainerHigh") ?: scheme.surfaceContainerHigh,
            surfaceContainerHighest = o.color("surfaceContainerHighest") ?: scheme.surfaceContainerHighest,
            outline = o.color("outline") ?: scheme.outline,
            outlineVariant = o.color("outlineVariant") ?: scheme.outlineVariant,
            onBackground = o.color("onBackground") ?: scheme.onBackground,
            onSurface = o.color("onSurface") ?: o.color("onBackground") ?: scheme.onSurface,
            onSurfaceVariant = o.color("onSurfaceVariant") ?: scheme.onSurfaceVariant,
            inverseSurface = o.color("inverseSurface") ?: scheme.inverseSurface,
            inverseOnSurface = o.color("inverseOnSurface") ?: scheme.inverseOnSurface,
            error = o.color("error") ?: scheme.error,
            errorContainer = o.color("errorContainer") ?: scheme.errorContainer,
            onErrorContainer = o.color("onErrorContainer") ?: scheme.onErrorContainer,
        )
    }

    fun extra(base: ExtraColors, overrides: Map<String, Long>): ExtraColors {
        if (overrides.isEmpty()) return base
        val o = overrides
        return base.copy(
            diffAddedBackground = o.color("diffAddedBackground") ?: base.diffAddedBackground,
            diffAddedText = o.color("diffAddedText") ?: base.diffAddedText,
            diffRemovedBackground = o.color("diffRemovedBackground") ?: base.diffRemovedBackground,
            diffRemovedText = o.color("diffRemovedText") ?: base.diffRemovedText,
            diffHunkBackground = o.color("diffHunkBackground") ?: base.diffHunkBackground,
            diffHunkText = o.color("diffHunkText") ?: base.diffHunkText,
            syntaxKeyword = o.color("syntaxKeyword") ?: base.syntaxKeyword,
            syntaxString = o.color("syntaxString") ?: base.syntaxString,
            syntaxComment = o.color("syntaxComment") ?: base.syntaxComment,
            syntaxNumber = o.color("syntaxNumber") ?: base.syntaxNumber,
            statusOk = o.color("statusOk") ?: base.statusOk,
            statusWarn = o.color("statusWarn") ?: base.statusWarn,
            contextInput = o.color("contextInput") ?: base.contextInput,
            contextCacheRead = o.color("contextCacheRead") ?: base.contextCacheRead,
            contextCacheWrite = o.color("contextCacheWrite") ?: base.contextCacheWrite,
            contextOutput = o.color("contextOutput") ?: base.contextOutput,
            contextReasoning = o.color("contextReasoning") ?: base.contextReasoning,
        )
    }

    fun argb(color: Color): Long = color.toArgb().toLong() and 0xFFFFFFFFL

    /** `#RRGGBB` or `#AARRGGBB` → ARGB, null when it is not a colour. */
    fun parseHex(text: String): Long? {
        val hex = text.trim().removePrefix("#")
        val value = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> 0xFF000000L or value
            8 -> value
            3 -> {
                val r = (value shr 8) and 0xF
                val g = (value shr 4) and 0xF
                val b = value and 0xF
                0xFF000000L or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            else -> null
        }
    }

    fun hex(argb: Long): String {
        val alpha = (argb shr 24) and 0xFF
        return if (alpha == 0xFFL) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb and 0xFFFFFFFFL)
    }
}
