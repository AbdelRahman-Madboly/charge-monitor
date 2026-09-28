package com.vinex.chargemonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    surfaceContainer = Color(0xFFECEEF0),
    background = Color(0xFFF6F7F8),
    surface = Color(0xFFF6F7F8),
    surfaceVariant = Color(0xFFE4E7EA),
    onSurfaceVariant = Color(0xFF454C53),
    outline = Color(0xFF8A939B),
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
    surfaceContainer = Color(0xFF181B1E),
    background = Color(0xFF101214),
    surface = Color(0xFF101214),
    surfaceVariant = Color(0xFF1E2226),
    onSurfaceVariant = Color(0xFFB9C0C6),
    outline = Color(0xFF5C646B),
    error = Color(0xFFEF5350),
)

/**
 * Tabular numerals for readouts, so digits do not shift width as values change every tick.
 * Monospace keeps that without bundling a font file.
 */
object Readout {
    val hero = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 56.sp)
    val tile = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
}

// Dense instrument style: slightly smaller body and label sizes than the Material defaults.
private val DenseTypography = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = 15.sp),
        bodyMedium = bodyMedium.copy(fontSize = 13.sp),
        labelLarge = labelLarge.copy(fontSize = 13.sp),
        labelMedium = labelMedium.copy(fontSize = 11.sp),
    )
}

@Composable
fun ChargeMonitorTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = DenseTypography,
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

/** Palette offered for new power banks; distinct from each other and from the built-in colours. */
val BankPalette: List<Int> = listOf(
    0xFFE57373, 0xFFFFB74D, 0xFFFFD54F, 0xFF4DB6AC, 0xFF4FC3F7, 0xFF7986CB, 0xFFF06292, 0xFFA1887F,
).map { it.toInt() }
