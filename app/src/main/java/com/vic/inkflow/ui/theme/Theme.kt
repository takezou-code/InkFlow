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

private val DarkColorScheme = darkColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Slate700,
    onPrimaryContainer = InkTextStrongDark,
    secondary = BrandPurple,
    onSecondary = Color.White,
    secondaryContainer = Slate800,
    onSecondaryContainer = InkTextStrongDark,
    tertiary = BrandCyan,
    onTertiary = Slate900,
    background = Slate900,
    onBackground = InkTextStrongDark,
    surface = Slate800,
    onSurface = InkTextStrongDark,
    surfaceVariant = Slate800,
    onSurfaceVariant = InkTextSoftDark,
    surfaceContainerLowest = WorkspaceDeskDark,
    surfaceContainerLow = Slate850,
    surfaceContainer = Slate800,
    surfaceContainerHigh = Slate700,
    surfaceContainerHighest = Slate700,
    outline = Slate500,
    outlineVariant = Slate700,
    error = Color(0xFFFF7A8A)
)

private val LightColorScheme = lightColorScheme(
    primary = BrandIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = InkTextStrong,
    secondary = BrandPurple,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E8FF),
    onSecondaryContainer = InkTextStrong,
    tertiary = BrandAmber,
    onTertiary = InkTextStrong,
    background = Slate50,
    onBackground = InkTextStrong,
    surface = Color(0xFFFFFFFF),
    onSurface = InkTextStrong,
    surfaceVariant = Slate100,
    onSurfaceVariant = InkTextSoft,
    surfaceContainerLowest = WorkspaceDeskLight,
    surfaceContainerLow = Color(0xFFF5F8FC),
    surfaceContainer = Slate100,
    surfaceContainerHigh = Color(0xFFEEF3F9),
    surfaceContainerHighest = Slate150,
    outline = Slate300,
    outlineVariant = Slate200,
    error = Color(0xFFD94C62)
)

@Composable
fun InkFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
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