package ai.opencode.android.ui.theme

import ai.opencode.android.settings.AppearanceSettings
import ai.opencode.android.settings.FontChoice
import ai.opencode.android.settings.ThemeMode
import ai.opencode.android.settings.ThemePalette
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Whether the resolved theme is dark (user choice applied, not just the system setting). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** Multiplier for chat message text, from Settings → Appearance. */
val LocalChatTextScale = staticCompositionLocalOf { 1f }

/** True when the device can render [ThemePalette.DYNAMIC] (Material You, Android 12+). */
val dynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun AppearanceSettings.isDark(): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun OpencodeTheme(
    appearance: AppearanceSettings = AppearanceSettings(),
    content: @Composable () -> Unit,
) {
    val dark = appearance.isDark()
    val context = LocalContext.current
    val custom = appearance.custom
    val overrides = custom.colors(dark)
    val colors = remember(dark, appearance.palette, appearance.pureBlack, overrides) {
        val base = when {
            appearance.palette == ThemePalette.DYNAMIC && dynamicColorAvailable ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            else -> schemeFor(accentFor(appearance.palette), dark)
        }
        val scheme = if (dark && appearance.pureBlack) base.pureBlack() else base
        ColorRoles.apply(scheme, overrides)
    }
    val extra = remember(dark, overrides, colors) { ColorRoles.extra(ExtraColors.defaults(dark, colors), overrides) }
    val typography = remember(custom.uiFont, custom.lineHeightScale) { typographyFor(custom.uiFont.family(), custom.lineHeightScale) }
    val shapes = remember(custom.cornerScale) { shapesFor(custom.cornerScale) }
    val density = LocalDensity.current
    val scaledDensity = remember(density, custom.uiFontScale) {
        if (custom.uiFontScale == 1f) density else Density(density.density, density.fontScale * custom.uiFontScale)
    }
    val tokens = remember(custom) { UiTokens.from(custom) }
    CompositionLocalProvider(
        LocalDarkTheme provides dark,
        LocalChatTextScale provides appearance.chatTextScale.factor * custom.chatFontScale,
        LocalCodeTextScale provides custom.codeFontScale,
        LocalExtraColors provides extra,
        LocalUiTokens provides tokens,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

/** The swatch shown for a palette in the appearance picker (its dark-mode primary). */
fun paletteSwatch(palette: ThemePalette, dark: Boolean): Color =
    if (dark) accentFor(palette).dark.primary else accentFor(palette).light.primary

// ---------------------------------------------------------------------------------- palettes

private data class AccentTones(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

/** Canvas and text tones; accents without their own use the shared cool greys. */
private data class Neutrals(
    val background: Color,
    val onBackground: Color,
    val onSurfaceVariant: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val containerLowest: Color,
    val containerLow: Color,
    val container: Color,
    val containerHigh: Color,
    val containerHighest: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
)

private data class Accent(
    val light: AccentTones,
    val dark: AccentTones,
    val lightNeutrals: Neutrals? = null,
    val darkNeutrals: Neutrals? = null,
)

/** Claude Code: terracotta accent on warm paper / warm charcoal. */
private val Claude = Accent(
    light = AccentTones(
        Color(0xFFC96442), Color.White, Color(0xFFF7DCD0), Color(0xFF3D1406),
        Color(0xFF6B675E), Color.White, Color(0xFFE9E5DA), Color(0xFF26241F),
        Color(0xFF4F6BAE), Color.White, Color(0xFFDDE3F5), Color(0xFF0F1B3D),
    ),
    dark = AccentTones(
        Color(0xFFD97757), Color(0xFF2B1209), Color(0xFF5B2E1E), Color(0xFFFFDBCE),
        Color(0xFFC3BFB4), Color(0xFF2A2824), Color(0xFF3B3934), Color(0xFFE8E4D8),
        Color(0xFF9DB1E6), Color(0xFF14234A), Color(0xFF2C3B66), Color(0xFFDDE3F5),
    ),
    lightNeutrals = Neutrals(
        background = Color(0xFFFAF9F5), onBackground = Color(0xFF141413),
        onSurfaceVariant = Color(0xFF5E5D59), surfaceVariant = Color(0xFFE8E6DC),
        outline = Color(0xFF8A887F), outlineVariant = Color(0xFFD8D5CA),
        containerLowest = Color.White, containerLow = Color(0xFFF5F4EE), container = Color(0xFFF0EEE6),
        containerHigh = Color(0xFFE8E6DC), containerHighest = Color(0xFFDEDCD1),
        inverseSurface = Color(0xFF30302E), inverseOnSurface = Color(0xFFF5F4EE),
    ),
    darkNeutrals = Neutrals(
        background = Color(0xFF262624), onBackground = Color(0xFFF0EEE6),
        onSurfaceVariant = Color(0xFFA9A69C), surfaceVariant = Color(0xFF3A3935),
        outline = Color(0xFF6E6C66), outlineVariant = Color(0xFF3E3D39),
        containerLowest = Color(0xFF1C1C1A), containerLow = Color(0xFF2B2A27), container = Color(0xFF30302E),
        containerHigh = Color(0xFF393835), containerHighest = Color(0xFF43423E),
        inverseSurface = Color(0xFFF0EEE6), inverseOnSurface = Color(0xFF262624),
    ),
)

private val Opencode = Accent(
    light = AccentTones(
        Color(0xFF00658C), Color.White, Color(0xFFC7E7F8), Color(0xFF001E2C),
        Color(0xFF4C6272), Color.White, Color(0xFFD0E4F4), Color(0xFF071E2B),
        Color(0xFF4F6538), Color.White, Color(0xFFD1EBB2), Color(0xFF0D2000),
    ),
    dark = AccentTones(
        Color(0xFF7DD3FC), Color(0xFF04222F), Color(0xFF0B3A52), Color(0xFFD3EEFF),
        Color(0xFF9AB4C8), Color(0xFF0B1B24), Color(0xFF1D2C36), Color(0xFFD6E6F2),
        Color(0xFFB4C7A1), Color(0xFF1F3311), Color(0xFF354A27), Color(0xFFD0E8BB),
    ),
)

private val Emerald = Accent(
    light = AccentTones(
        Color(0xFF006C45), Color.White, Color(0xFF8BF8C0), Color(0xFF002113),
        Color(0xFF4E6355), Color.White, Color(0xFFD0E8D8), Color(0xFF0B1F14),
        Color(0xFF3B6470), Color.White, Color(0xFFBEE9F8), Color(0xFF001F26),
    ),
    dark = AccentTones(
        Color(0xFF6EDBA5), Color(0xFF00391F), Color(0xFF005231), Color(0xFF8BF8C0),
        Color(0xFFB4CCBC), Color(0xFF203529), Color(0xFF364B3F), Color(0xFFD0E8D8),
        Color(0xFFA3CDDB), Color(0xFF033640), Color(0xFF214C57), Color(0xFFBEE9F8),
    ),
)

private val Amber = Accent(
    light = AccentTones(
        Color(0xFF845400), Color.White, Color(0xFFFFDDB6), Color(0xFF2A1800),
        Color(0xFF6F5B40), Color.White, Color(0xFFFADEBC), Color(0xFF271904),
        Color(0xFF516440), Color.White, Color(0xFFD4EABB), Color(0xFF0F2004),
    ),
    dark = AccentTones(
        Color(0xFFFFB95C), Color(0xFF462A00), Color(0xFF643F00), Color(0xFFFFDDB6),
        Color(0xFFDDC2A1), Color(0xFF3E2D16), Color(0xFF56432A), Color(0xFFFADEBC),
        Color(0xFFB8CEA1), Color(0xFF243516), Color(0xFF3A4C2A), Color(0xFFD4EABB),
    ),
)

private val Violet = Accent(
    light = AccentTones(
        Color(0xFF65558F), Color.White, Color(0xFFE9DDFF), Color(0xFF201047),
        Color(0xFF625B71), Color.White, Color(0xFFE8DEF8), Color(0xFF1E192B),
        Color(0xFF7E5260), Color.White, Color(0xFFFFD9E3), Color(0xFF31101D),
    ),
    dark = AccentTones(
        Color(0xFFCFBDFE), Color(0xFF36275D), Color(0xFF4D3D75), Color(0xFFE9DDFF),
        Color(0xFFCBC2DB), Color(0xFF332D41), Color(0xFF4A4458), Color(0xFFE8DEF8),
        Color(0xFFEFB8C8), Color(0xFF4A2532), Color(0xFF633B48), Color(0xFFFFD9E3),
    ),
)

private val Rose = Accent(
    light = AccentTones(
        Color(0xFF8E4955), Color.White, Color(0xFFFFD9DD), Color(0xFF3B0714),
        Color(0xFF75565A), Color.White, Color(0xFFFFD9DD), Color(0xFF2C1518),
        Color(0xFF7B5733), Color.White, Color(0xFFFFDDB4), Color(0xFF2D1600),
    ),
    dark = AccentTones(
        Color(0xFFFFB2BC), Color(0xFF561D29), Color(0xFF72333F), Color(0xFFFFD9DD),
        Color(0xFFE5BDC1), Color(0xFF43292D), Color(0xFF5C3F43), Color(0xFFFFD9DD),
        Color(0xFFEBC08A), Color(0xFF452B02), Color(0xFF5F4116), Color(0xFFFFDDB4),
    ),
)

private fun accentFor(palette: ThemePalette): Accent = when (palette) {
    ThemePalette.CLAUDE -> Claude
    ThemePalette.OPENCODE, ThemePalette.DYNAMIC -> Opencode
    ThemePalette.EMERALD -> Emerald
    ThemePalette.AMBER -> Amber
    ThemePalette.VIOLET -> Violet
    ThemePalette.ROSE -> Rose
}

/** Neutral surfaces are shared by every accent: cool greys that keep code and diffs readable. */
private fun schemeFor(accent: Accent, dark: Boolean): ColorScheme {
    val neutrals = if (dark) accent.darkNeutrals else accent.lightNeutrals
    return schemeWithDefaultNeutrals(accent, dark).let { scheme ->
        if (neutrals == null) scheme else scheme.withNeutrals(neutrals)
    }
}

private fun ColorScheme.withNeutrals(n: Neutrals): ColorScheme = copy(
    background = n.background, onBackground = n.onBackground,
    surface = n.background, onSurface = n.onBackground,
    surfaceVariant = n.surfaceVariant, onSurfaceVariant = n.onSurfaceVariant,
    outline = n.outline, outlineVariant = n.outlineVariant,
    surfaceBright = n.containerHighest, surfaceDim = n.background,
    surfaceContainerLowest = n.containerLowest, surfaceContainerLow = n.containerLow,
    surfaceContainer = n.container, surfaceContainerHigh = n.containerHigh,
    surfaceContainerHighest = n.containerHighest,
    inverseSurface = n.inverseSurface, inverseOnSurface = n.inverseOnSurface,
)

private fun schemeWithDefaultNeutrals(accent: Accent, dark: Boolean): ColorScheme {
    if (dark) {
        val t = accent.dark
        return darkColorScheme(
            primary = t.primary, onPrimary = t.onPrimary,
            primaryContainer = t.primaryContainer, onPrimaryContainer = t.onPrimaryContainer,
            inversePrimary = accent.light.primary,
            secondary = t.secondary, onSecondary = t.onSecondary,
            secondaryContainer = t.secondaryContainer, onSecondaryContainer = t.onSecondaryContainer,
            tertiary = t.tertiary, onTertiary = t.onTertiary,
            tertiaryContainer = t.tertiaryContainer, onTertiaryContainer = t.onTertiaryContainer,
            background = Color(0xFF0E1014), onBackground = Color(0xFFE3E7EC),
            surface = Color(0xFF0E1014), onSurface = Color(0xFFE3E7EC),
            surfaceVariant = Color(0xFF1E232A), onSurfaceVariant = Color(0xFFB0BAC5),
            surfaceTint = t.primary,
            inverseSurface = Color(0xFFE3E7EC), inverseOnSurface = Color(0xFF1B1E22),
            error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
            outline = Color(0xFF5B646E), outlineVariant = Color(0xFF2E353D),
            scrim = Color.Black,
            surfaceBright = Color(0xFF33383F), surfaceDim = Color(0xFF0E1014),
            surfaceContainerLowest = Color(0xFF090B0E), surfaceContainerLow = Color(0xFF14171C),
            surfaceContainer = Color(0xFF181C21), surfaceContainerHigh = Color(0xFF22272D),
            surfaceContainerHighest = Color(0xFF2D3238),
        )
    }
    val t = accent.light
    return lightColorScheme(
        primary = t.primary, onPrimary = t.onPrimary,
        primaryContainer = t.primaryContainer, onPrimaryContainer = t.onPrimaryContainer,
        inversePrimary = accent.dark.primary,
        secondary = t.secondary, onSecondary = t.onSecondary,
        secondaryContainer = t.secondaryContainer, onSecondaryContainer = t.onSecondaryContainer,
        tertiary = t.tertiary, onTertiary = t.onTertiary,
        tertiaryContainer = t.tertiaryContainer, onTertiaryContainer = t.onTertiaryContainer,
        background = Color(0xFFF8FAFC), onBackground = Color(0xFF171C21),
        surface = Color(0xFFF8FAFC), onSurface = Color(0xFF171C21),
        surfaceVariant = Color(0xFFDFE5EB), onSurfaceVariant = Color(0xFF424B55),
        surfaceTint = t.primary,
        inverseSurface = Color(0xFF2C3136), inverseOnSurface = Color(0xFFEEF1F4),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF727C86), outlineVariant = Color(0xFFC2CAD2),
        scrim = Color.Black,
        surfaceBright = Color(0xFFF8FAFC), surfaceDim = Color(0xFFD7DCE1),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4F7),
        surfaceContainer = Color(0xFFEBEEF2), surfaceContainerHigh = Color(0xFFE5E9ED),
        surfaceContainerHighest = Color(0xFFDFE3E8),
    )
}

/** OLED variant: true black canvas, containers lifted just enough to stay distinguishable. */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0B0D),
    surfaceContainer = Color(0xFF101215),
    surfaceContainerHigh = Color(0xFF181B1F),
    surfaceContainerHighest = Color(0xFF202429),
    surfaceVariant = Color(0xFF181B1F),
)

// ---------------------------------------------------------------------------------- type & shape

private val DefaultTypography = Typography()

val OpencodeTypography = typographyFor(FontFamily.Default, 1f)

/** The app's type scale in [family], line heights multiplied by [lineHeight]. */
fun typographyFor(family: FontFamily, lineHeight: Float): Typography {
    fun TextStyle.styled(weight: FontWeight? = null, height: TextUnit? = null): TextStyle {
        val base = (height ?: this.lineHeight)
        return copy(
            fontFamily = family,
            fontWeight = weight ?: fontWeight,
            lineHeight = if (base.isSpecified && lineHeight != 1f) base * lineHeight else base,
        )
    }
    val d = DefaultTypography
    return Typography(
        displayLarge = d.displayLarge.styled(),
        displayMedium = d.displayMedium.styled(),
        displaySmall = d.displaySmall.styled(FontWeight.SemiBold),
        headlineLarge = d.headlineLarge.styled(FontWeight.SemiBold),
        headlineMedium = d.headlineMedium.styled(FontWeight.SemiBold),
        headlineSmall = d.headlineSmall.styled(FontWeight.SemiBold),
        titleLarge = d.titleLarge.styled(FontWeight.SemiBold),
        titleMedium = d.titleMedium.styled(FontWeight.SemiBold),
        titleSmall = d.titleSmall.styled(FontWeight.SemiBold),
        bodyLarge = d.bodyLarge.styled(height = 24.sp),
        bodyMedium = d.bodyMedium.styled(height = 21.sp),
        bodySmall = d.bodySmall.styled(),
        labelLarge = d.labelLarge.styled(FontWeight.SemiBold),
        labelMedium = d.labelMedium.styled(),
        labelSmall = d.labelSmall.styled(),
    )
}

val OpencodeShapes = shapesFor(1f)

/** Material shapes with every radius multiplied by [scale] (0 = square corners). */
fun shapesFor(scale: Float): Shapes = Shapes(
    extraSmall = RoundedCornerShape((6 * scale).dp),
    small = RoundedCornerShape((10 * scale).dp),
    medium = RoundedCornerShape((16 * scale).dp),
    large = RoundedCornerShape((24 * scale).dp),
    extraLarge = RoundedCornerShape((32 * scale).dp),
)

fun FontChoice.family(): FontFamily = when (this) {
    FontChoice.SYSTEM -> FontFamily.Default
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.MONO -> FontFamily.Monospace
}

// ---------------------------------------------------------------------------------- diff colours

data class DiffPalette(
    val addedBackground: Color,
    val addedText: Color,
    val removedBackground: Color,
    val removedText: Color,
    val hunkBackground: Color,
    val hunkText: Color,
    val noteText: Color,
)

@Composable
@ReadOnlyComposable
fun diffPalette(): DiffPalette {
    val extra = LocalExtraColors.current
    return DiffPalette(
        addedBackground = extra.diffAddedBackground,
        addedText = extra.diffAddedText,
        removedBackground = extra.diffRemovedBackground,
        removedText = extra.diffRemovedText,
        hunkBackground = extra.diffHunkBackground,
        hunkText = extra.diffHunkText,
        noteText = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Semantic status colours that read well on both themes (connection dots, health); user-editable. */
object StatusColors {
    val ok: Color
        @Composable @ReadOnlyComposable
        get() = LocalExtraColors.current.statusOk
    val warn: Color
        @Composable @ReadOnlyComposable
        get() = LocalExtraColors.current.statusWarn
}
