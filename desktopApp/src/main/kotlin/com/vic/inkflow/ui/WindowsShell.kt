package com.vic.inkflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Windows 11–style Mica backdrop: a soft vertical gradient that tracks the
 * current theme instead of the tablet's multi-orb AuroraBackground.
 *
 * The ramp comes from the theme, not from literal hex codes: a hardcoded ramp
 * is a second, invisible theme that only gets noticed when the user switches
 * to dark mode and the window keeps its old colours (AGENTS #13 - one accent
 * variable, and no literals in product UI).
 */
@Composable
fun MicaBackground(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(
        scheme.background,
        scheme.surface,
        scheme.surfaceVariant
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors))
    ) {
        content()
    }
}