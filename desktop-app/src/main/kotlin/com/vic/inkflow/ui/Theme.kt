package com.vic.inkflow.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * InkFlow Material Design 3 palette.
 * Primary tone: Slate (deep grey-blue). Accent: Blue.
 */
private object InkColors {
    // Slate scale
    val Slate200 = Color(0xFFBFC9DA)
    val Slate500 = Color(0xFF5B6B85)
    val Slate700 = Color(0xFF3A4759)
    val Slate900 = Color(0xFF1E2733)

    // Blue accent
    val Blue400 = Color(0xFF5B9BF5)
    val Blue500 = Color(0xFF3B82F6)
    val Blue800 = Color(0xFF1E4E9E)

    // Neutrals
    val SurfaceLight = Color(0xFFF6F7FB)
    val SurfaceDark = Color(0xFF141A22)
    val CardDark = Color(0xFF1D2530)
    val OutlineDark = Color(0xFF3A4453)
}

private val LightInkScheme: ColorScheme = lightColorScheme(
    primary = InkColors.Blue800,
    onPrimary = Color.White,
    primaryContainer = InkColors.Slate200,
    onPrimaryContainer = InkColors.Slate900,
    secondary = InkColors.Slate700,
    secondaryContainer = Color(0xFFDCE3EE),
    onSecondaryContainer = InkColors.Slate900,
    background = InkColors.SurfaceLight,
    surface = Color.White,
    surfaceVariant = Color(0xFFE4E9F1),
    onSurfaceVariant = InkColors.Slate700,
    outline = Color(0xFF9AA6B8)
)

private val DarkInkScheme: ColorScheme = darkColorScheme(
    primary = InkColors.Blue400,
    onPrimary = Color(0xFF0A1C38),
    primaryContainer = InkColors.Blue800,
    onPrimaryContainer = Color(0xFFDCE9FF),
    secondary = InkColors.Slate200,
    secondaryContainer = InkColors.Slate700,
    onSecondaryContainer = Color(0xFFE7ECF4),
    tertiary = InkColors.Blue500,
    background = InkColors.SurfaceDark,
    surface = Color(0xFF181F29),
    surfaceVariant = InkColors.CardDark,
    onSurface = Color(0xFFE3E8F0),
    onSurfaceVariant = InkColors.Slate200,
    outline = InkColors.OutlineDark,
    outlineVariant = Color(0xFF2A3340)
)

/** Document cards use 12dp rounded corners per the design spec. */
val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

val InkTypography = Typography()

/** Global dark-mode toggle (defaults to dark, per reading-scenario spec). */
object InkThemeState {
    var darkMode by mutableStateOf(true)
}

@Composable
fun InkFlowTheme(
    darkTheme: Boolean = InkThemeState.darkMode,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkInkScheme else LightInkScheme,
        typography = InkTypography,
        shapes = InkShapes,
        content = content
    )
}
