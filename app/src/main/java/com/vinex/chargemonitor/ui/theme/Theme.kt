package com.vinex.chargemonitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
