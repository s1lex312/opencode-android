package ai.opencode.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * opencode's terminal-inspired palette: near-black surfaces, cyan accents, warm error red.
 * Dynamic colour (Material You) is intentionally not used so the app keeps its identity and
 * diff colours stay predictable.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF04222F),
    primaryContainer = Color(0xFF0B3A52),
    onPrimaryContainer = Color(0xFFD3EEFF),
    secondary = Color(0xFF9AB4C8),
    onSecondary = Color(0xFF0B1B24),
    secondaryContainer = Color(0xFF1D2C36),
    onSecondaryContainer = Color(0xFFD6E6F2),
    tertiary = Color(0xFFB4C7A1),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFE7EBF0),
    surface = Color(0xFF12151A),
    onSurface = Color(0xFFE7EBF0),
    surfaceVariant = Color(0xFF1B2027),
    onSurfaceVariant = Color(0xFFAEB9C6),
    surfaceContainer = Color(0xFF141920),
    surfaceContainerHigh = Color(0xFF1A2028),
    outline = Color(0xFF39414B),
    outlineVariant = Color(0xFF262D35),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF3A0908),
    errorContainer = Color(0xFF4B1512),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7E7F8),
    onPrimaryContainer = Color(0xFF001E2C),
    secondary = Color(0xFF4C6272),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E4F4),
    onSecondaryContainer = Color(0xFF071E2B),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF161C21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161C21),
    surfaceVariant = Color(0xFFE7EDF3),
    onSurfaceVariant = Color(0xFF44515D),
    outline = Color(0xFF74828F),
    outlineVariant = Color(0xFFCBD5DE),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

@Composable
fun OpencodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

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
fun diffPalette(): DiffPalette = if (isSystemInDarkTheme()) {
    DiffPalette(
        addedBackground = Color(0xFF122B1D),
        addedText = Color(0xFF9BE9A8),
        removedBackground = Color(0xFF341A1A),
        removedText = Color(0xFFFFB3AD),
        hunkBackground = Color(0xFF16222E),
        hunkText = Color(0xFF8FC7E8),
        noteText = MaterialTheme.colorScheme.onSurfaceVariant,
    )
} else {
    DiffPalette(
        addedBackground = Color(0xFFE6F6EA),
        addedText = Color(0xFF1B5E20),
        removedBackground = Color(0xFFFDECEA),
        removedText = Color(0xFFB3261E),
        hunkBackground = Color(0xFFE8F1F8),
        hunkText = Color(0xFF0B4F6C),
        noteText = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
