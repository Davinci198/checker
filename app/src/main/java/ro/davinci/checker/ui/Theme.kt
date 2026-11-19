package ro.davinci.checker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF4ADE80)
private val Blue = Color(0xFF38BDF8)
private val Violet = Color(0xFFA78BFA)
private val Amber = Color(0xFFFBBF24)
private val Red = Color(0xFFF87171)

private val CheckerColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF052012),
    primaryContainer = Color(0xFF12351F),
    onPrimaryContainer = Color(0xFFB7F7CC),
    secondary = Blue,
    onSecondary = Color(0xFF00212E),
    secondaryContainer = Color(0xFF0F2C3B),
    onSecondaryContainer = Color(0xFFC3E9FA),
    tertiary = Violet,
    onTertiary = Color(0xFF1B1033),
    tertiaryContainer = Color(0xFF2A1F47),
    onTertiaryContainer = Color(0xFFDDD1FF),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF0F1526),
    onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF19203A),
    onSurfaceVariant = Color(0xFF9AA9C4),
    outline = Color(0xFF2B3657),
    outlineVariant = Color(0xFF1E2742),
    error = Red,
    onError = Color(0xFF2A0808),
    errorContainer = Color(0xFF3B1414),
    onErrorContainer = Color(0xFFFFD7D7),
    inverseSurface = Color(0xFFE6EDF7),
    inverseOnSurface = Color(0xFF0B1020),
)

val CheckerAmber = Amber
val CheckerBlue = Blue
val CheckerViolet = Violet
val CheckerRed = Red
val CheckerGreen = Accent

@Composable
fun CheckerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CheckerColors,
        typography = Typography(),
        content = content,
    )
}