package com.vic.inkflow.ui.theme

import android.app.Activity
import android.os.Build
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
import androidx.core.view.WindowCompat

enum class BrandTheme(val primary: Color, val secondary: Color) {
    INDIGO(BrandIndigo, BrandPurple),
    ROSE(Color(0xFFEF4444), Color(0xFFF97373)),
    OCEAN(Color(0xFF0284C7), Color(0xFF38BDF8)),
    FOREST(Color(0xFF059669), Color(0xFF34D399)),
    VIOLET(Color(0xFF7C3AED), Color(0xFFA78BFA)),
    TEAL(Color(0xFF0F766E), Color(0xFF2DD4BF)),
    SUNSET(Color(0xFFF97316), Color(0xFFFBBF24)),
    MONOCHROME(Color(0xFF475569), Color(0xFF94A3B8))
}

private fun BrandTheme.lightBackground(): Color = when (this) {
    BrandTheme.ROSE -> Color(0xFFFFF2F3)
    BrandTheme.OCEAN -> Color(0xFFF0FAFF)
    BrandTheme.FOREST -> Color(0xFFF0FAF4)
    BrandTheme.VIOLET -> Color(0xFFF7F2FF)
    BrandTheme.TEAL -> Color(0xFFF0FCFA)
    BrandTheme.SUNSET -> Color(0xFFFFF7EE)
    BrandTheme.MONOCHROME -> Slate50
    BrandTheme.INDIGO -> Slate50
}

private fun BrandTheme.lightSurface(): Color = when (this) {
    BrandTheme.ROSE -> Color(0xFFFFFBFB)
    BrandTheme.OCEAN -> Color(0xFFF8FCFF)
    BrandTheme.FOREST -> Color(0xFFF8FBF9)
    BrandTheme.VIOLET -> Color(0xFFFCF9FF)
    BrandTheme.TEAL -> Color(0xFFF8FFFE)
    BrandTheme.SUNSET -> Color(0xFFFFFBF5)
    BrandTheme.MONOCHROME -> Color(0xFFFFFFFF)
    BrandTheme.INDIGO -> Color(0xFFFFFFFF)
}

private fun BrandTheme.darkBackground(): Color = when (this) {
    BrandTheme.ROSE -> Color(0xFF17090B)
    BrandTheme.OCEAN -> Color(0xFF071621)
    BrandTheme.FOREST -> Color(0xFF071913)
    BrandTheme.VIOLET -> Color(0xFF130A1F)
    BrandTheme.TEAL -> Color(0xFF071B1A)
    BrandTheme.SUNSET -> Color(0xFF1D1008)
    BrandTheme.MONOCHROME -> Slate900
    BrandTheme.INDIGO -> Slate900
}

private fun BrandTheme.darkSurface(): Color = when (this) {
    BrandTheme.ROSE -> Color(0xFF241014)
    BrandTheme.OCEAN -> Color(0xFF102233)
    BrandTheme.FOREST -> Color(0xFF10251B)
    BrandTheme.VIOLET -> Color(0xFF1B132B)
    BrandTheme.TEAL -> Color(0xFF0D2423)
    BrandTheme.SUNSET -> Color(0xFF2A170F)
    BrandTheme.MONOCHROME -> Slate800
    BrandTheme.INDIGO -> Slate800
}

@Composable
fun InkFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    brandTheme: BrandTheme = BrandTheme.INDIGO,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkColorScheme = darkColorScheme(
        primary = brandTheme.primary,
        onPrimary = Color.White,
        primaryContainer = brandTheme.primary.copy(alpha = 0.2f),
        onPrimaryContainer = InkTextStrongDark,
        secondary = brandTheme.secondary,
        onSecondary = Color.White,
        secondaryContainer = brandTheme.secondary.copy(alpha = 0.2f),
        onSecondaryContainer = InkTextStrongDark,
        tertiary = brandTheme.secondary.copy(alpha = 0.85f),
        onTertiary = Color.White,
        background = brandTheme.darkBackground(),
        onBackground = InkTextStrongDark,
        surface = brandTheme.darkSurface(),
        onSurface = InkTextStrongDark,
        surfaceVariant = brandTheme.darkSurface(),
        onSurfaceVariant = InkTextSoftDark,
        surfaceContainerLowest = brandTheme.darkBackground(),
        surfaceContainerLow = brandTheme.darkSurface(),
        surfaceContainer = brandTheme.darkSurface(),
        surfaceContainerHigh = brandTheme.primary.copy(alpha = 0.12f),
        surfaceContainerHighest = brandTheme.primary.copy(alpha = 0.16f),
        outline = brandTheme.primary.copy(alpha = 0.05f),
        outlineVariant = brandTheme.secondary.copy(alpha = 0.03f),
        error = Color(0xFFFF7A8A)
    )

    val lightColorScheme = lightColorScheme(
        primary = brandTheme.primary,
        onPrimary = Color.White,
        primaryContainer = brandTheme.primary.copy(alpha = 0.12f),
        onPrimaryContainer = InkTextStrong,
        secondary = brandTheme.secondary,
        onSecondary = Color.White,
        secondaryContainer = brandTheme.secondary.copy(alpha = 0.12f),
        onSecondaryContainer = InkTextStrong,
        tertiary = brandTheme.secondary.copy(alpha = 0.82f),
        onTertiary = InkTextStrong,
        background = brandTheme.lightBackground(),
        onBackground = InkTextStrong,
        surface = brandTheme.lightSurface(),
        onSurface = InkTextStrong,
        surfaceVariant = brandTheme.lightSurface(),
        onSurfaceVariant = InkTextSoft,
        surfaceContainerLowest = brandTheme.lightBackground(),
        surfaceContainerLow = brandTheme.lightSurface(),
        surfaceContainer = brandTheme.lightSurface(),
        surfaceContainerHigh = brandTheme.primary.copy(alpha = 0.08f),
        surfaceContainerHighest = brandTheme.primary.copy(alpha = 0.12f),
        outline = brandTheme.primary.copy(alpha = 0.10f),
        outlineVariant = brandTheme.secondary.copy(alpha = 0.06f),
        error = Color(0xFFD94C62)
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
        content = content
    )
}