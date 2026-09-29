package com.vinex.chargemonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The icon's body green; the app's single accent. */
val Accent = Color(0xFF5FBF3F)

/** Deeper icon green, used where the accent sits on a light surface and needs text contrast. */
private val AccentDeep = Color(0xFF3B8F2B)

private val LightColors = lightColorScheme(
    primary = AccentDeep,
    onPrimary = Color.White,
    primaryContainer = Accent,
    onPrimaryContainer = Color(0xFF0B1A06),
    secondary = Color(0xFF55606A),
    secondaryContainer = Color(0xFFD6EBCC),
    onSecondaryContainer = Color(0xFF10230A),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    // Neutral greys for dialogs, sheets, fields and switch tracks; the Material baseline tints these purple.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF7F8F9),
    surfaceContainerHighest = Color(0xFFE8EBEE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDE1E5),
    inverseSurface = Color(0xFF2A2F33),
    inverseOnSurface = Color(0xFFEEF1F3),
    inversePrimary = Accent,
    tertiary = Color(0xFF2F6DB5),
    onTertiary = Color.White,
    errorContainer = Color(0xFFFDE3E1),
    onErrorContainer = Color(0xFF5F1210),
    background = Color(0xFFF1F3F5),
    onBackground = Color(0xFF15191C),
    surface = Color(0xFFF1F3F5),
    onSurface = Color(0xFF15191C),
    surfaceVariant = Color(0xFFE4E7EA),
    onSurfaceVariant = Color(0xFF4A525A),
    outline = Color(0xFF8A939B),
    outlineVariant = Color(0xFFDDE2E6),
    error = Color(0xFFC62828),
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF0B1A06),
    primaryContainer = AccentDeep,
    onPrimaryContainer = Color(0xFFE6F6DD),
    secondary = Color(0xFFA8B0B8),
    secondaryContainer = Color(0xFF2A3A24),
    onSecondaryContainer = Color(0xFFD6EBCC),
    surfaceContainer = Color(0xFF16191C),
    surfaceContainerLow = Color(0xFF1A1E22),
    surfaceContainerLowest = Color(0xFF0B0D0F),
    surfaceContainerHigh = Color(0xFF22272B),
    surfaceContainerHighest = Color(0xFF2B3035),
    surfaceBright = Color(0xFF2B3035),
    surfaceDim = Color(0xFF0E1012),
    inverseSurface = Color(0xFFE6E9EC),
    inverseOnSurface = Color(0xFF1A1E22),
    inversePrimary = AccentDeep,
    tertiary = Color(0xFF6FA8E8),
    onTertiary = Color(0xFF0A1A2C),
    errorContainer = Color(0xFF4A1715),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1012),
    onBackground = Color(0xFFE6E9EC),
    surface = Color(0xFF0E1012),
    onSurface = Color(0xFFE6E9EC),
    surfaceVariant = Color(0xFF1E2226),
    onSurfaceVariant = Color(0xFFB4BCC3),
    outline = Color(0xFF5C646B),
    outlineVariant = Color(0xFF272C31),
    error = Color(0xFFEF5350),
)

/**
 * Tabular numerals for readouts, so digits do not shift width as values change every tick.
 * Monospace keeps that without bundling a font file.
 */
object Readout {
    val hero = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 56.sp)
    val tile = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
}

// Readable instrument style: Material sizes for body and label text, with a little extra weight
// on titles so section and card headings stand out on a dense screen.
private val AppTypography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = labelLarge.copy(fontSize = 14.sp),
        labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp),
    )
}

/** Corner radii: 16 dp for cards and panels, smaller for chips and fields. */
object Corners {
    val panel = 16.dp
    val tile = 12.dp
    val chip = 8.dp
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(Corners.chip),
    medium = RoundedCornerShape(Corners.tile),
    large = RoundedCornerShape(Corners.panel),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ChargeMonitorTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/**
 * Chart series colours, one hue per quantity everywhere in the app. Each has a darker light-theme
 * variant so thin lines keep at least 3:1 against the light surface.
 */
object SeriesColors {
    private val isDark: Boolean
        @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val power: Color
        @Composable get() = MaterialTheme.colorScheme.primary
    val current: Color
        @Composable get() = if (isDark) Color(0xFF6FA8E8) else Color(0xFF2F6DB5)
    val voltage: Color
        @Composable get() = if (isDark) Color(0xFFE8B04A) else Color(0xFFA86A00)
    val level: Color
        @Composable get() = if (isDark) Color(0xFFB39DDB) else Color(0xFF7154B0)
}

/**
 * Palette offered for new power banks and chargers; distinct from each other and from the
 * built-in colours. Ordered so that neighbours differ in hue, since new sources take the first
 * unused entry and two sources added together should never look alike on a chart.
 */
val BankPalette: List<Int> = listOf(
    0xFFE57373, 0xFF4FC3F7, 0xFFFFB74D, 0xFF7986CB, 0xFF4DB6AC, 0xFFF06292, 0xFFFFD54F, 0xFFA1887F,
).map { it.toInt() }
