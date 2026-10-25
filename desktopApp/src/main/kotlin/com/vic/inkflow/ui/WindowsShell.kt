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
 */
@Composable
fun MicaBackground(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val colors = if (isDark) listOf(
        Color(0xFF0B0D12),
        Color(0xFF151821),
        Color(0xFF1C202B)
    ) else listOf(
        Color(0xFFF7F8FB),
        Color(0xFFEEF1F7),
        Color(0xFFE6E9F2)
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors))
    ) {
        content()
    }
}