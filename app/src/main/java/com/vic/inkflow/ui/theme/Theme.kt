package com.vic.inkflow.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Composable
fun InkFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkColorScheme = darkColorScheme(
        primary = BrandIndigo,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E7FF),
        onPrimaryContainer = Color(0xFF1E1B4B),
        secondary = BrandPurple,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFEDE9FE),
        onSecondaryContainer = Color(0xFF2E1065),
        tertiary = Color(0xFF06B6D4),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFCFFAFE),
        onTertiaryContainer = Color(0xFF083344),
        background = Color(0xFF09090B),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF18181B),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF27272A),
        onSurfaceVariant = Color(0xFF94A3B8),
        surfaceTint = BrandIndigo,
        inverseSurface = Color(0xFFF8FAFC),
        inverseOnSurface = Color(0xFF18181B),
        inversePrimary = Color(0xFF818CF8),
        surfaceDim = Color(0xFF09090B),
        surfaceBright = Color(0xFF27272A),
        surfaceContainerLowest = Color(0xFF09090B),
        surfaceContainerLow = Color(0xFF18181B),
        surfaceContainer = Color(0xFF1F1F23),
        surfaceContainerHigh = Color(0xFF27272A),
        surfaceContainerHighest = Color(0xFF3F3F46),
        outline = Color(0xFFE2E8F0),
        outlineVariant = Color(0xFFF1F5F9),
        scrim = Color(0xFF000000),
        error = Color(0xFFEF4444),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF450A0A)
    )

    val lightColorScheme = lightColorScheme(
        primary = BrandIndigo,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE0E7FF),
        onPrimaryContainer = Color(0xFF1E1B4B),
        secondary = BrandPurple,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFEDE9FE),
        onSecondaryContainer = Color(0xFF2E1065),
        tertiary = Color(0xFF06B6D4),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFCFFAFE),
        onTertiaryContainer = Color(0xFF083344),
        background = Color(0xFFF8FAFC),
        onBackground = Color(0xFF0F172A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFF1F5F9),
        onSurfaceVariant = Color(0xFF64748B),
        surfaceTint = BrandIndigo,
        inverseSurface = Color(0xFF0F172A),
        inverseOnSurface = Color(0xFFF8FAFC),
        inversePrimary = Color(0xFF818CF8),
        surfaceDim = Color(0xFFF1F5F9),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8FAFC),
        surfaceContainer = Color(0xFFF1F5F9),
        surfaceContainerHigh = Color(0xFFE2E8F0),
        surfaceContainerHighest = Color(0xFFCBD5E1),
        outline = Color(0xFFCBD5E1),
        outlineVariant = Color(0xFFE2E8F0),
        scrim = Color(0xFF000000),
        error = Color(0xFFEF4444),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF450A0A)
    )

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme
        else -> lightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = ShapeSm,
            medium = ShapeMd,
            large = ShapeLg,
            extraLarge = ShapeXl
        ),
        content = content
    )
}
